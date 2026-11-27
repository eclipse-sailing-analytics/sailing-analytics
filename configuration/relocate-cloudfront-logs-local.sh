#!/usr/bin/env python3
"""Mirror the S3 CloudFront consolidation on the LOCAL cache.

Moves the historical CloudFront .gz files from the local merged S3-access tree into the local
v2 path, matching S3. Driven by the S3 v2 listing, so it only touches the ~153k CloudFront files
and never scans the 11.5M local S3-access logs.

Run AFTER consolidate-cloudfront-logs.sh has populated the S3 v2 prefix.

  local src : <BASE>/logs/media.sapsailing.com017363970217/eu-west-1/media.sapsailing.com/YYYY/MM/DD/E2YEQ...gz
  local dst : <BASE>/AWSLogs/017363970217/CloudFront/media.sapsailing.com/YYYY/MM/DD/E2YEQ...gz

Needs boto3 (for the S3 listing only). No S3 writes. Metadata-only local renames.
"""

import argparse, os, sys, time

# ================= CONFIG — verify =================
BUCKET     = "sapsailing-access-logs"
REGION     = "eu-west-1"
LOCAL_BASE = "/var/log/old/cache/aws/sapsailing-access-logs"
V2_PREFIX  = "AWSLogs/017363970217/CloudFront/media.sapsailing.com"   # S3 prefix of consolidated CF logs
LOCAL_SRC  = os.path.join(LOCAL_BASE, "logs/media.sapsailing.com017363970217/eu-west-1/media.sapsailing.com")
LOCAL_DST  = os.path.join(LOCAL_BASE, V2_PREFIX)
# ===================================================

T0 = time.monotonic()
def log(m): print(f"[{time.strftime('%H:%M:%S')} +{time.monotonic()-T0:6.0f}s] {m}", flush=True)

def main():
    ap = argparse.ArgumentParser(description="Mirror S3 CloudFront consolidation onto the local cache (renames).")
    ap.add_argument("--dry-run", action="store_true", help="preview, no renames")
    args = ap.parse_args()
    try:
        import boto3
    except ImportError:
        sys.exit("boto3 not installed:  sudo dnf install -y python3-boto3")

    s3 = boto3.client("s3", region_name=REGION)
    pag = s3.get_paginator("list_objects_v2")
    made, moved, missing, shown = set(), 0, 0, 0
    log(f"Driving from s3://{BUCKET}/{V2_PREFIX}/  ({'DRY-RUN' if args.dry_run else 'LIVE'})")
    for page in pag.paginate(Bucket=BUCKET, Prefix=V2_PREFIX + "/"):
        for o in page.get("Contents", []):
            tail = o["Key"][len(V2_PREFIX):].lstrip("/")        # YYYY/MM/DD/filename
            src = os.path.join(LOCAL_SRC, tail)
            dst = os.path.join(LOCAL_DST, tail)
            if not os.path.exists(src):                          # not in local cache, or already moved (resume)
                missing += 1
                continue
            if args.dry_run:
                if shown < 5:
                    log(f"WOULD mv {src} -> {dst}"); shown += 1
                moved += 1
                continue
            dd = os.path.dirname(dst)
            if dd not in made:
                os.makedirs(dd, exist_ok=True); made.add(dd)
            try:
                os.rename(src, dst)
                moved += 1
            except FileNotFoundError:
                missing += 1
            if moved % 20_000 == 0:
                log(f"  moved {moved:,} (not-local {missing:,})")
    log(f"{'WOULD move' if args.dry_run else 'DONE'}: moved {moved:,}, not-local {missing:,}")

if __name__ == "__main__":
    main()
