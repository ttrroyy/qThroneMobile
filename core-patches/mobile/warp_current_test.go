package mobile

import "testing"

func TestWarpRegistrationRequiresLiveInstance(t *testing.T) {
	for _, current := range []*Instance{nil, {}, {started: true, closed: true}} {
		if _, err := current.WarpRegister(&WarpRegisterRequest{TunnelType: "wireguard"}); err == nil {
			t.Fatal("registration must not fall back to a direct request")
		}
	}
}
