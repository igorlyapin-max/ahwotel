package main

import (
	"encoding/json"
	"os/exec"
	"strings"
	"testing"
	"time"
)

func TestChildExitPreservesCodeAndSignal(t *testing.T) {
	for _, test := range []struct { script string; code, signal int }{
		{"exit 0", 0, 0}, {"exit 17", 17, 0}, {"kill -KILL $$", -1, 9},
	} {
		code, signal := childExit(exec.Command("/bin/sh", "-c", test.script).Run())
		if code != test.code || signal != test.signal { t.Fatalf("got %d/%d want %d/%d", code, signal, test.code, test.signal) }
	}
}

func encode(p policy) []byte { data, _ := json.Marshal(p); return data }

func TestDeadlineSurvivesRestartAndCannotBeExtendedByClock(t *testing.T) {
	now := time.Now()
	p := policy{1, "Verbose", "a", float64(now.Unix()), float64(now.Unix() + 60)}
	var e evaluator
	if e.evaluate(encode(p), now).Effective != "Verbose" {
		t.Fatal("lease not applied")
	}
	if e.evaluate(encode(p), now.Add(61*time.Second)).Effective != "Off" {
		t.Fatal("lease not expired")
	}
	// Same token remains expired even if wall time returns into the original lease.
	e.deadline = now.Add(-time.Second)
	if e.evaluate(encode(p), now).Effective != "Off" {
		t.Fatal("lease extended")
	}
	var restarted evaluator
	if restarted.evaluate(encode(p), now.Add(time.Minute)).Effective != "Off" {
		t.Fatal("reboot revived expired lease")
	}
	p.Token = "b"
	p.Issued = float64(now.Unix() + 61)
	p.Expires = p.Issued + 60
	if e.evaluate(encode(p), now.Add(61*time.Second)).Effective != "Verbose" {
		t.Fatal("new lease not applied")
	}
}

func TestMalformedOrUnboundedPolicyIsOff(t *testing.T) {
	now := time.Now()
	samples := [][]byte{nil, []byte("broken"), []byte(`{"level":"Verbose"}`),
		encode(policy{1, "Verbose", "a", float64(now.Unix()), float64(now.Unix() + 1801)}),
		encode(policy{1, "Verbose", "a", float64(now.Unix() + 60), float64(now.Unix() + 120)})}
	for _, raw := range samples {
		var e evaluator
		if e.evaluate(raw, now).Effective != "Off" {
			t.Fatal("invalid policy enabled logging")
		}
	}
	for _, level := range []string{"Off", "Basic"} {
		var e evaluator
		if e.evaluate(encode(policy{Version: 1, Level: level, Token: "a"}), now).Effective != level {
			t.Fatal(level)
		}
	}
}

func TestChangingDiagnosticsPreservesDeploymentArguments(t *testing.T) {
	for _, service := range []string{"collector", "prometheus", "grafana"} {
		args := []string{"--storage.tsdb.retention.time=30d", "--log.level=debug", "--web.listen-address=:9097"}
		cmd, err := childCommand(service, "Off", args, []string{"GF_LOG_LEVEL=debug", "OTEL_LOG_LEVEL=debug", "UNCHANGED=yes"})
		if err != nil {
			t.Fatal(err)
		}
		joined := strings.Join(cmd.Args, " ")
		if !strings.Contains(joined, "retention.time=30d") || !strings.Contains(joined, ":9097") {
			t.Fatal("deployment changed")
		}
		if !strings.Contains(strings.Join(cmd.Env, " "), "UNCHANGED=yes") {
			t.Fatal("environment changed")
		}
		if service == "prometheus" && (strings.Contains(joined, "--log.level=debug") || !strings.Contains(joined, "--log.level=warn")) {
			t.Fatal(joined)
		}
	}
}
