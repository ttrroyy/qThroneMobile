package mobile

import "testing"

func TestMobileAPIsWithoutRunningInstance(t *testing.T) {
	if _, err := FetchThroughProxy(nil, "https://example.test", "test", 1000, 4096); err == nil {
		t.Fatal("fetch without a running instance must fail")
	}
	if _, err := ResolveDomains(nil, nil, nil); err == nil {
		t.Fatal("nil resolve request accepted")
	}
	result, err := ResolveDomains(nil, nil, &ResolveRequest{})
	if err != nil || result.Len() != 0 {
		t.Fatalf("empty resolve request: %v, %v", result, err)
	}
}
