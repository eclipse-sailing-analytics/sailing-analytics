#!/usr/bin/env python3
"""One-time: move historical CloudFront logs out of the merged S3-access tree into the
CloudFront standard-logging-v2 location, keeping the legacy filenames and the YYYY/MM/DD
partition. Server-side copy+delete; lists ONLY CloudFront objects (per-day, by distribution-id
prefix) so it never scans the 11.5M S3-access logs.

  Source : logs/media.sapsailing.com017363970217/eu-west-1/media.sapsailing.com/YYYY/MM/DD/E2YEQ...gz
  Dest   : AWSLogs/017363970217/CloudFront/cloudfront/media.sapsailing.com/YYYY/MM/DD/E2YEQ...gz

  Needs boto3.  Run under the instance role (auto-refresh) or a long-lived token.
"""

import argparse, datetime, re, sys, time
from concurrent.futures import ThreadPoolExecutor, as_completed

# ================= CONFIG — verify =================
BUCKET   = "sapsailing-access-logs"
REGION   = "eu-west-1"
DIST_ID  = "E2YEQ22MXCKC5R"
SRC_ROOT = "logs/media.sapsailing.com017363970217/eu-west-1/media.sapsailing.com"   # merged tree
DEST_ROOT = "AWSLogs/017363970217/CloudFront/media.sapsailing.com"                   # v2 location (trimmed, matches console)
START_DAY = datetime.date(2021, 4, 1)      # a little before the first known CF log (2021-04-15)
WORKERS  = 32
# ===================================================

EXPIRED = {"ExpiredToken", "ExpiredTokenException", "RequestExpired", "InvalidToken", "TokenRefreshRequired"}
def _expired(e):
    import botocore.exceptions
    return isinstance(e, botocore.exceptions.ClientError) and \
           e.response.get("Error", {}).get("Code") in EXPIRED

T0 = time.monotonic()
def log(m): print(f"[{time.strftime('%H:%M:%S')} +{time.monotonic()-T0:6.0f}s] {m}", flush=True)

def main():
    ap = argparse.ArgumentParser(description="Consolidate historical CloudFront logs into the v2 location.")
    ap.add_argument("--dry-run", action="store_true", help="preview, no copy/delete")
    ap.add_argument("--workers", type=int, default=WORKERS)
    ap.add_argument("--start", help="first day YYYY-MM-DD (default 2021-04-01)")
    ap.add_argument("--end", help="last day YYYY-MM-DD (default today)")
    args = ap.parse_args()
    try:
        import boto3
    except ImportError:
        sys.exit("boto3 not installed:  sudo dnf install -y python3-boto3")

    start = datetime.date.fromisoformat(args.start) if args.start else START_DAY
    end   = datetime.date.fromisoformat(args.end) if args.end else datetime.date.today()
    log(f"Scope: {start}..{end} | workers {args.workers} | {'DRY-RUN' if args.dry_run else 'LIVE'}")

    s3 = boto3.client("s3", region_name=REGION)
    pag = s3.get_paginator("list_objects_v2")
    copied = deleted = skipped = shown = 0
    nr = 50_000
    ex = ThreadPoolExecutor(max_workers=args.workers)
    try:
        day = start
        while day <= end:
            y, mo, d = day.strftime("%Y"), day.strftime("%m"), day.strftime("%d")
            # list only this day's CloudFront objects (prefix includes the distribution id):
            src_prefix = f"{SRC_ROOT}/{y}/{mo}/{d}/{DIST_ID}."
            for page in pag.paginate(Bucket=BUCKET, Prefix=src_prefix):
                pairs = []
                for o in page.get("Contents", []):
                    key = o["Key"]
                    if not key.endswith(".gz"):
                        continue
                    name = key.rsplit("/", 1)[-1]
                    sc = o.get("StorageClass", "STANDARD")
                    if sc in ("GLACIER", "DEEP_ARCHIVE"):
                        log(f"SKIP archived {key} ({sc})"); skipped += 1; continue
                    pairs.append((key, f"{DEST_ROOT}/{y}/{mo}/{d}/{name}", sc))
                if not pairs:
                    continue
                if args.dry_run:
                    while shown < 5:
                        log(f"WOULD copy {pairs[0][0]} -> {pairs[0][1]}"); shown += 1; break
                    copied += len(pairs); continue
                futs = {ex.submit(s3.copy_object, Bucket=BUCKET, Key=nk,
                                  CopySource={"Bucket": BUCKET, "Key": ok}, StorageClass=sc): ok
                        for (ok, nk, sc) in pairs}
                done = []
                for fut in as_completed(futs):
                    ok = futs[fut]
                    try: fut.result(); done.append(ok)
                    except Exception as e:
                        if _expired(e): raise
                        log(f"COPY FAIL {ok}: {e}")
                copied += len(done)
                for i in range(0, len(done), 1000):
                    chunk = done[i:i+1000]
                    s3.delete_objects(Bucket=BUCKET,
                                      Delete={"Objects": [{"Key": k} for k in chunk], "Quiet": True})
                    deleted += len(chunk)
                if copied >= nr:
                    log(f"  copied {copied:,} deleted {deleted:,} "
                        f"{copied/max(time.monotonic()-T0,1e-9):,.0f}/s"); nr += 50_000
            day += datetime.timedelta(days=1)
    except Exception as e:
        if not _expired(e):
            raise
        log(f"CREDENTIALS EXPIRED after copied {copied:,} / deleted {deleted:,} — renew and re-run")
        sys.exit(2)
    finally:
        ex.shutdown(wait=True, cancel_futures=True)
    log(f"{'WOULD move' if args.dry_run else 'DONE'}: copied {copied:,} deleted {deleted:,} skipped {skipped:,}")

if __name__ == "__main__":
    main()
