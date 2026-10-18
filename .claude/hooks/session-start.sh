#!/bin/bash
# SessionStart hook for Claude Code cloud sessions.
# Installs RTK, Graphify and Ponytail when missing and builds the code-only
# Graphify graph. Each step is idempotent; a failed step is reported and the
# session still starts with ordinary tools.
set -uo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

BIN="$HOME/.local/bin"
export PATH="$BIN:$PATH"
if [ -n "${CLAUDE_ENV_FILE:-}" ] && ! grep -qs "$BIN" "$CLAUDE_ENV_FILE"; then
  echo "export PATH=\"$BIN:\$PATH\"" >> "$CLAUDE_ENV_FILE"
fi

warn() { echo "session-start: $*" >&2; }

# RTK: official install.sh, pinned to the latest release tag. The tag comes
# from git because the installer's own lookup goes through the GitHub API,
# which cloud proxies may refuse. The installer verifies checksums.
install_rtk() {
  local tag
  tag=$(git ls-remote --tags --refs https://github.com/rtk-ai/rtk.git 'v*' \
    | sed 's|.*refs/tags/||' | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -1)
  [ -n "$tag" ] || return 1
  curl -fsSL "https://raw.githubusercontent.com/rtk-ai/rtk/refs/tags/$tag/install.sh" \
    | RTK_VERSION="$tag" sh
}
command -v rtk >/dev/null 2>&1 || install_rtk >&2 || warn "RTK was not installed"

# Graphify: CLI from PyPI (package graphifyy).
if ! command -v graphify >/dev/null 2>&1; then
  if command -v uv >/dev/null 2>&1; then
    uv tool install graphifyy >&2
  else
    python3 -m pip install --user graphifyy >&2
  fi || warn "Graphify was not installed"
fi

# Ponytail: Claude Code plugin. .claude/settings.json also declares it, this
# covers hosts that do not install project-declared plugins on their own.
if command -v claude >/dev/null 2>&1 && ! claude plugin list 2>/dev/null | grep -q 'ponytail@ponytail'; then
  { claude plugin marketplace add DietrichGebert/ponytail \
    && claude plugin install ponytail@ponytail; } >&2 || warn "Ponytail was not installed"
fi

# Code-only graph (local AST, no LLM). Rebuilt only when missing; after code
# changes run `graphify update .`.
cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0
if command -v graphify >/dev/null 2>&1 && [ ! -f graphify-out/graph.json ]; then
  mkdir -p graphify-out
  graphify extract . --code-only > graphify-out/extract.log 2>&1 \
    || warn "graph build failed, see graphify-out/extract.log"
fi

exit 0
