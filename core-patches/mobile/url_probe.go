package mobile

import (
	"context"
	"sync"
	"time"

	"qThroneCore/internal/probe"
	E "github.com/sagernet/sing/common/exceptions"
)

// URLTestTask cancels just this probe, including its pending outbound dials.
// It never closes the live instance or rearms the process-wide test context.
type URLTestTask struct{ cancel context.CancelFunc }

func (t *URLTestTask) Cancel() { t.cancel() }

func StartScopedURLTest(current *Instance, platform PlatformInterface, request *TestRequest, handler URLTestHandler) (*URLTestTask, error) {
	if request == nil || handler == nil {
		return nil, E.New("nil request or handler")
	}
	env, err := prepareTestEnv(current, request.TestCurrent, platform, request)
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(probe.TestContext())
	task := &URLTestTask{cancel: cancel}
	if request.TestCurrent {
		ctx = probe.LiveInstance(ctx)
	}
	go func() {
		// Closing Xray can take seconds; a repeated ping must not wait for it.
		defer func() { go env.close() }()
		defer handler.OnDone()
		defer cancel()
		var mu sync.Mutex
		failed := make(map[string]bool)
		// Publish directly to this handler. Concurrent probes must not drain and
		// discard each other's results from the shared desktop polling buffer.
		results := probe.BatchURLTestTo(ctx, env.box, env.tags, request.URL,
			int(request.MaxConcurrency), !request.TestCurrent,
			time.Duration(request.TimeoutMs)*time.Millisecond,
			func(result *probe.URLTestResult) {
				mu.Lock()
				failed[result.Tag] = result.Error != nil
				mu.Unlock()
				handler.OnResult(result.Tag, int32(result.Duration.Milliseconds()), errorString(result.Error))
			},
			// The pinned core lacks the desktop's cold allowance; the unmeasured first request dials the server.
			coldRequestAllowance(!request.TestCurrent))
		// Aborted tags are deliberately not published by BatchURLTestTo.
		for _, result := range results {
			if result.Error == probe.ErrTestAborted {
				handler.OnResult(result.Tag, 0, errorString(result.Error))
			}
		}
		for _, tag := range request.vpnEndpointTags {
			if failed[tag] && ctx.Err() == nil {
				connected, state, failure := vpnEndpointVerdict(env.box, tag)
				handler.OnVPNStatus(tag, connected, state, failure)
			}
		}
	}()
	return task, nil
}

// Matches the desktop's probe.ColdRequestAllowance: five more seconds for the unmeasured first request.
func coldRequestAllowance(twice bool) time.Duration {
	if twice {
		return 5 * time.Second
	}
	return 0
}
