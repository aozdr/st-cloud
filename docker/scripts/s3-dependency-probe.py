"""S3 SigV4 功能探针，仅用 Python 标准库；写模式使用随机保留审计对象。"""
import argparse
import datetime
import hashlib
import hmac
import json
import os
import sys
import urllib.parse
import urllib.request
import uuid


def request(method, endpoint, bucket, key, body=b""):
    parsed = urllib.parse.urlsplit(endpoint)
    if parsed.scheme not in ("http", "https") or not parsed.netloc or parsed.query:
        raise ValueError("invalid S3 endpoint")
    path = parsed.path.rstrip("/") + "/" + urllib.parse.quote(bucket, safe="")
    if key:
        path += "/" + urllib.parse.quote(key, safe="/")
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    day = stamp[:8]
    region = os.getenv("STCLOUD_S3_REGION", "us-east-1")
    access = os.getenv("STCLOUD_S3_ACCESS_KEY", "stcloud")
    secret = os.getenv("STCLOUD_S3_SECRET_KEY", "stcloud123")
    content_hash = hashlib.sha256(body).hexdigest()
    headers = {"host": parsed.netloc, "x-amz-content-sha256": content_hash, "x-amz-date": stamp}
    canonical_headers = "".join(name + ":" + value + "\n" for name, value in sorted(headers.items()))
    signed_headers = ";".join(sorted(headers))
    canonical = "\n".join((method, path, "", canonical_headers, signed_headers, content_hash))
    scope = "/".join((day, region, "s3", "aws4_request"))
    string_to_sign = "\n".join(("AWS4-HMAC-SHA256", stamp, scope, hashlib.sha256(canonical.encode()).hexdigest()))
    signature_key = ("AWS4" + secret).encode()
    for value in (day, region, "s3", "aws4_request"):
        signature_key = hmac.new(signature_key, value.encode(), hashlib.sha256).digest()
    signature = hmac.new(signature_key, string_to_sign.encode(), hashlib.sha256).hexdigest()
    headers["Authorization"] = "AWS4-HMAC-SHA256 Credential=" + access + "/" + scope + ", SignedHeaders=" + signed_headers + ", Signature=" + signature
    req = urllib.request.Request(parsed.scheme + "://" + parsed.netloc + path, data=body if method == "PUT" else None, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=8) as response:
        return response.status, response.read()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--endpoint", default=os.getenv("STCLOUD_S3_ENDPOINT", "http://127.0.0.1:9000"))
    parser.add_argument("--bucket", default=os.getenv("STCLOUD_S3_BUCKET", "stcloud"))
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    request("HEAD", args.endpoint, args.bucket, "")
    print("S3_AUTHENTICATED_HEAD_PASS bucket=" + args.bucket)
    if args.write:
        suffix = uuid.uuid4().hex
        probe_day = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d")
        key = "environment-probes/" + probe_day + "/" + suffix + ".txt"
        body = ("stcloud dependency probe " + suffix).encode()
        request("PUT", args.endpoint, args.bucket, key, body)
        status, actual = request("GET", args.endpoint, args.bucket, key)
        if actual != body:
            raise RuntimeError("S3 round trip payload mismatch")
        print(json.dumps({"result": "S3_WRITE_READ_PASS", "bucket": args.bucket, "key": key, "payloadBytes": len(body), "sha256": hashlib.sha256(body).hexdigest(), "retained": True}))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # 不打印请求头、签名或 S3 凭据。
        print("S3_PROBE_FAIL " + type(error).__name__ + ": " + str(error), file=sys.stderr)
        sys.exit(1)
