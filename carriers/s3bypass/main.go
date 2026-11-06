// Command rawline-s3bypass runs one S3 Bypass profile for RawlineMobile: the
// fedarisha outbound of Fedarisha/Xray-core-fedarisha (what the original S3
// Bypass app runs) behind an Xray SOCKS5 inbound on loopback that requires the
// per-launch credentials. Only the parts of Xray this needs are linked.
package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"

	"github.com/xtls/xray-core/core"

	_ "github.com/xtls/xray-core/app/dispatcher"
	_ "github.com/xtls/xray-core/app/log"
	_ "github.com/xtls/xray-core/app/policy"
	_ "github.com/xtls/xray-core/app/proxyman/inbound"
	_ "github.com/xtls/xray-core/app/proxyman/outbound"
	_ "github.com/xtls/xray-core/main/json"
	_ "github.com/xtls/xray-core/proxy/fedarisha"
	_ "github.com/xtls/xray-core/proxy/socks"
	_ "github.com/xtls/xray-core/transport/internet/tagged/taggedimpl"
	_ "github.com/xtls/xray-core/transport/internet/tcp"
	_ "github.com/xtls/xray-core/transport/internet/udp"
)

type profile struct {
	Storage   map[string]any `json:"storage"`
	Tuning    map[string]any `json:"tuning"`
	SOCKS     string         `json:"socks"`
	SOCKSUser string         `json:"socks_user"`
	SOCKSPass string         `json:"socks_pass"`
}

// xrayConfig builds the client side of the original app's configuration.
func xrayConfig(p profile) ([]byte, error) {
	host, port, err := net.SplitHostPort(p.SOCKS)
	listenPort, _ := strconv.Atoi(port)
	if err != nil || host != "127.0.0.1" || listenPort < 1 || listenPort > 65535 {
		return nil, errors.New("listener must be IPv4 loopback")
	}
	if len(p.SOCKSUser) < 1 || len(p.SOCKSUser) > 255 || len(p.SOCKSPass) < 1 || len(p.SOCKSPass) > 255 {
		return nil, errors.New("authenticated bridge required")
	}
	for _, key := range []string{"bucket", "endpoint", "accessKey", "secretKey"} {
		if value, _ := p.Storage[key].(string); strings.TrimSpace(value) == "" {
			return nil, fmt.Errorf("storage %s required", key)
		}
	}
	if p.Storage["type"] == nil {
		p.Storage["type"] = "s3"
	}
	settings := map[string]any{"storage": p.Storage}
	if len(p.Tuning) > 0 {
		settings["tuning"] = p.Tuning
	}
	return json.Marshal(map[string]any{
		"log": map[string]any{"loglevel": "warning"},
		"inbounds": []any{map[string]any{
			"tag": "socks-in", "listen": "127.0.0.1", "port": listenPort, "protocol": "socks",
			"settings": map[string]any{
				"auth":     "password",
				"accounts": []any{map[string]any{"user": p.SOCKSUser, "pass": p.SOCKSPass}},
				"udp":      true,
			},
		}},
		"outbounds": []any{map[string]any{"tag": "proxy", "protocol": "fedarisha", "settings": settings}},
	})
}

func run() int {
	if len(os.Args) != 3 || os.Args[1] != "--rawline-config" {
		fmt.Fprintln(os.Stderr, "S3 Bypass: expected private configuration file")
		return 2
	}
	data, err := os.ReadFile(os.Args[2])
	var p profile
	if err != nil || len(data) > 1<<20 || json.Unmarshal(data, &p) != nil || p.Storage == nil {
		fmt.Fprintln(os.Stderr, "S3 Bypass: invalid configuration")
		return 2
	}
	raw, err := xrayConfig(p)
	if err != nil {
		fmt.Fprintf(os.Stderr, "S3 Bypass: %v\n", err)
		return 2
	}
	config, err := core.LoadConfig("json", bytes.NewReader(raw))
	if err != nil {
		fmt.Fprintf(os.Stderr, "S3 Bypass: %v\n", err)
		return 2
	}
	instance, err := core.New(config)
	if err != nil {
		fmt.Fprintf(os.Stderr, "S3 Bypass: %v\n", err)
		return 1
	}
	if err := instance.Start(); err != nil {
		fmt.Fprintf(os.Stderr, "S3 Bypass: %v\n", err)
		return 1
	}
	defer instance.Close()
	fmt.Println("[S3] READY")

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	go func() {
		scanner := bufio.NewScanner(io.LimitReader(os.Stdin, 4096))
		for scanner.Scan() {
			if strings.TrimSpace(scanner.Text()) == "STOP" {
				break
			}
		}
		stop <- os.Interrupt
	}()
	<-stop
	return 0
}

func main() { os.Exit(run()) }
