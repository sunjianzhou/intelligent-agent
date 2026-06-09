#!/usr/bin/env sh
# 生成本地自签名证书（开发用，生产环境用 Let's Encrypt）
# 用法：sh nginx/generate-certs.sh
set -e
mkdir -p "$(dirname "$0")/certs"
openssl req -x509 -nodes -days 3650 -newkey rsa:2048 \
    -keyout "$(dirname "$0")/certs/selfsigned.key" \
    -out    "$(dirname "$0")/certs/selfsigned.crt" \
    -subj "/C=CN/ST=Local/L=Local/O=IntelligentAgent/CN=localhost"
echo "证书已生成：nginx/certs/selfsigned.crt"
