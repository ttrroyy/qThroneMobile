"""Route WARP registration through the live core without opening a local proxy port."""
import shutil
import sys
from pathlib import Path
from patch_mobile_obfs import replace_once

ROOT = Path(__file__).resolve().parents[1]


def patch(core: Path):
    register = core / "internal/warp/register.go"
    replace_once(register, '\t"net/http"', '\t"net"\n\t"net/http"')
    signature = 'func Register(ctx context.Context, tunnelType string, proxy string, apiHosts []string) (*Identity, error) {'
    replace_once(register, signature, signature + '\n\treturn RegisterWithDialer(ctx, tunnelType, proxy, apiHosts, nil)\n}\n\nfunc RegisterWithDialer(ctx context.Context, tunnelType, proxy string, apiHosts []string, dial func(context.Context, string, string) (net.Conn, error)) (*Identity, error) {')
    replace_once(register, '\tdefer apiClient.Close()', '\tdefer apiClient.Close()\n\tif dial != nil {\n\t\ttransport := apiClient.httpClient.Transport.(*http.Transport)\n\t\ttransport.Proxy = nil\n\t\ttransport.DialContext = dial\n\t}')
    mobile = core / "mobile/warp.go"
    replace_once(mobile, '\t"context"', '\t"context"\n\t"net"')
    signature = 'func WarpRegister(request *WarpRegisterRequest) (*WarpIdentity, error) {'
    replace_once(mobile, signature, signature + '\n\treturn warpRegister(context.Background(), request, nil)\n}\n\nfunc warpRegister(parent context.Context, request *WarpRegisterRequest, dial func(context.Context, string, string) (net.Conn, error)) (*WarpIdentity, error) {')
    replace_once(mobile, 'context.WithTimeout(context.Background(), warpRegisterTimeout', 'context.WithTimeout(parent, warpRegisterTimeout')
    replace_once(mobile, 'warp.Register(ctx, request.TunnelType, request.Proxy, request.apiHosts)', 'warp.RegisterWithDialer(ctx, request.TunnelType, request.Proxy, request.apiHosts, dial)')
    shutil.copyfile(ROOT / "core-patches/mobile/warp_current.go", core / "mobile/warp_current.go")
    shutil.copyfile(ROOT / "core-patches/mobile/warp_current_test.go", core / "mobile/warp_current_test.go")
    shutil.copyfile(ROOT / "core-patches/warp/dialer_test.go", core / "internal/warp/dialer_test.go")


if __name__ == "__main__":
    patch(Path(sys.argv[1]))
