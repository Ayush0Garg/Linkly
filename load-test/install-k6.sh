#!/usr/bin/env bash
# k6 (the load generator) from conda-forge via micromamba, into the same folder scripts/dev-redis.sh uses. No root needed.
#   load-test/install-k6.sh        installs if missing and prints the k6 binary path
set -euo pipefail
HOME_DIR="${LINKLY_HOME:-$HOME/.local/share/linkly}"
ENV_DIR="$HOME_DIR/k6-env"
if [ ! -x "$ENV_DIR/bin/k6" ]; then
  MM="$HOME_DIR/micromamba-download/bin/micromamba"
  if [ ! -x "$MM" ]; then
    case "$(uname -s)-$(uname -m)" in
      Linux-x86_64) plat=linux-64 ;; Linux-aarch64) plat=linux-aarch64 ;; Darwin-arm64) plat=osx-arm64 ;; Darwin-x86_64) plat=osx-64 ;;
      *) echo "unsupported platform" >&2; exit 1 ;;
    esac
    mkdir -p "$HOME_DIR/micromamba-download" && cd "$HOME_DIR/micromamba-download"
    curl -fsSL "https://micro.mamba.pm/api/micromamba/$plat/latest" -o micromamba.tar.bz2 && tar -xjf micromamba.tar.bz2 bin/micromamba
  fi
  MAMBA_ROOT_PREFIX="$HOME_DIR/micromamba-root" "$MM" create -y -q -p "$ENV_DIR" -c conda-forge k6 >&2
fi
echo "$ENV_DIR/bin/k6"
