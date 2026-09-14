// Small PID 1 for the lab: no network, Docker socket, shell evaluation or dependencies.
package main

import (
	"encoding/json"
	"fmt"
	"math"
	"os"
	"os/exec"
	"os/signal"
	"strings"
	"syscall"
	"time"
)

const policyPath = "/etc/ahwotel-policy/debug.json"
const statusPath = "/run/ahwotel/status.json"

type policy struct {
	Version int     `json:"version"`
	Level   string  `json:"level"`
	Token   string  `json:"token"`
	Issued  float64 `json:"issued"`
	Expires float64 `json:"expires"`
}
type status struct {
	Requested string  `json:"requested"`
	Effective string  `json:"effective"`
	Token     string  `json:"token"`
	Expires   float64 `json:"expires"`
	ChildPID  int     `json:"child_pid"`
	Error     string  `json:"error,omitempty"`
	Updated   int64   `json:"updated"`
}
type evaluator struct {
	current  policy
	deadline time.Time
}

func (e *evaluator) evaluate(raw []byte, now time.Time) status {
	s := status{Requested: "Off", Effective: "Off", Updated: now.Unix()}
	var p policy
	if len(raw) > 4096 || json.Unmarshal(raw, &p) != nil || p.Version != 1 || p.Token == "" ||
		(p.Level != "Off" && p.Level != "Basic" && p.Level != "Verbose") {
		s.Error = "invalid_or_missing_policy"
		return s
	}
	s.Requested, s.Token, s.Expires = p.Level, p.Token, p.Expires
	if p.Level != "Verbose" {
		s.Effective = p.Level
		e.current = p
		return s
	}
	if math.IsNaN(p.Expires) || math.IsInf(p.Expires, 0) || p.Issued <= 0 ||
		p.Expires <= p.Issued || p.Expires-p.Issued > 1800 || p.Issued > float64(now.UnixNano())/1e9+1 {
		s.Error = "invalid_verbose_deadline"
		return s
	}
	expires := time.Unix(0, int64(p.Expires*1e9))
	if p != e.current {
		e.current = p
		// now.Add retains Go's monotonic clock. A backward clock adjustment cannot extend this lease.
		e.deadline = now.Add(expires.Sub(now))
	}
	if !now.Before(expires) || !now.Before(e.deadline) {
		s.Error = "verbose_expired"
		return s
	}
	s.Effective = "Verbose"
	return s
}

func event(name, level string) {
	// Never log child arguments, environment, raw policy or telemetry payloads.
	_ = json.NewEncoder(os.Stdout).Encode(map[string]string{
		"time": time.Now().UTC().Format(time.RFC3339Nano), "level": "info", "component": "lab_supervisor",
		"event": name, "diagnostic_level": level})
}

func saveStatus(s status) error {
	data, err := json.Marshal(s)
	if err != nil {
		return err
	}
	if err = os.WriteFile(statusPath+".tmp", data, 0600); err != nil {
		return err
	}
	return os.Rename(statusPath+".tmp", statusPath)
}

func childCommand(service, level string, args, env []string) (*exec.Cmd, error) {
	logLevel := map[string]string{"Off": "warn", "Basic": "info", "Verbose": "debug"}[level]
	var path, key string
	switch service {
	case "collector":
		path, key = "/otelcol-contrib", "OTEL_LOG_LEVEL"
	case "prometheus":
		path = "/bin/prometheus"
		filtered := make([]string, 0, len(args)+1)
		for i := 0; i < len(args); i++ {
			if args[i] == "--log.level" {
				i++
				continue
			}
			if !strings.HasPrefix(args[i], "--log.level=") {
				filtered = append(filtered, args[i])
			}
		}
		args = append(filtered, "--log.level="+logLevel)
	case "grafana":
		path, key = "/run.sh", "GF_LOG_LEVEL"
	default:
		return nil, fmt.Errorf("unknown_service")
	}
	childEnv := make([]string, 0, len(env)+1)
	for _, value := range env {
		if key == "" || !strings.HasPrefix(value, key+"=") {
			childEnv = append(childEnv, value)
		}
	}
	if key != "" {
		childEnv = append(childEnv, key+"="+logLevel)
	}
	cmd := exec.Command(path, args...)
	cmd.Env = childEnv
	cmd.Stdout, cmd.Stderr, cmd.Stdin = os.Stdout, os.Stderr, os.Stdin
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	return cmd, nil
}

func stopChild(cmd *exec.Cmd, done <-chan error) {
	_ = syscall.Kill(-cmd.Process.Pid, syscall.SIGTERM)
	timer := time.NewTimer(10 * time.Second)
	defer timer.Stop()
	select {
	case <-done:
	case <-timer.C:
		_ = syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL)
		<-done
	}
}

func run(service string, args []string) int {
	signals := make(chan os.Signal, 2)
	signal.Notify(signals, syscall.SIGTERM, syscall.SIGINT)
	defer signal.Stop(signals)
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	var eval evaluator
	read := func() status { raw, _ := os.ReadFile(policyPath); return eval.evaluate(raw, time.Now()) }
	current := read()
	for {
		cmd, err := childCommand(service, current.Effective, args, os.Environ())
		if err != nil {
			event("invalid_service", "Off")
			return 1
		}
		if err = cmd.Start(); err != nil {
			event("child_start_failed", current.Effective)
			return 1
		}
		done := make(chan error, 1)
		go func() { done <- cmd.Wait() }()
		current.ChildPID = cmd.Process.Pid
		if saveStatus(current) != nil {
			event("status_write_failed", current.Effective)
			stopChild(cmd, done)
			return 1
		}
		event("diagnostic_applied", current.Effective)
		restart := false
		for !restart {
			select {
			case <-signals:
				stopChild(cmd, done)
				event("supervisor_stopped", current.Effective)
				return 0
			case <-done:
				current.Error = "child_exited"
				current.ChildPID = 0
				_ = saveStatus(current)
				event("child_exited", current.Effective)
				return 1 // Docker restart policy recreates the process and rechecks the lease.
			case <-ticker.C:
				next := read()
				if next.Effective != current.Effective {
					event("diagnostic_changing", next.Effective)
					stopChild(cmd, done)
					// Re-read after shutdown: a new policy or expiry may have arrived meanwhile.
					current = read()
					restart = true
				} else {
					next.ChildPID = cmd.Process.Pid
					current = next
					if saveStatus(current) != nil {
						event("status_write_failed", current.Effective)
						stopChild(cmd, done)
						return 1
					}
				}
			}
		}
	}
}

func main() {
	if len(os.Args) == 2 && os.Args[1] == "--status" {
		data, err := os.ReadFile(statusPath)
		if err != nil {
			os.Exit(1)
		}
		fmt.Println(string(data))
		return
	}
	if len(os.Args) < 2 {
		event("missing_service", "Off")
		os.Exit(1)
	}
	os.Exit(run(os.Args[1], os.Args[2:]))
}
