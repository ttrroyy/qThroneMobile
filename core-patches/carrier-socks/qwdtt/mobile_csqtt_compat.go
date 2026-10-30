package main

import (
	"encoding/json"
	"errors"
	"os"
	"strings"
)

func mobileCSQTTTransportName(path string) (string, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return "", errors.New("cannot read private CSQTT configuration")
	}
	var config struct {
		Protocol string `json:"protocol_variant"`
	}
	if json.Unmarshal(data, &config) != nil {
		return "", errors.New("invalid private CSQTT configuration")
	}
	switch config.Protocol {
	case "", "auto", "legacy":
		return "libcsqtt_transport.so", nil
	case "compat":
		return "libcsqtt_compat.so", nil
	default:
		return "", errors.New("unsupported CSQTT protocol")
	}
}

// Only fixed diagnostic markers cross the process boundary; never raw provider output.
func mobileCSQTTHint(line string) string {
	if strings.Contains(line, "WRAP_AUTH_TIMEOUT") {
		return "[CSQTT] WRAP_AUTH_TIMEOUT"
	}
	// Our generated epoch is well-formed; old servers read its fields at different offsets.
	if strings.Contains(line, "FATAL_PROTOCOL:") || strings.Contains(line, "DENIED:invalid_epoch") ||
		strings.Contains(line, "(invalid_epoch)") {
		return "[CSQTT] FATAL_PROTOCOL:"
	}
	return ""
}
