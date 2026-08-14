#!/usr/bin/env python3
"""Refuse a bundle that Play would reject for size.

    check-bundle-size.py <app.aab>

The release workflow measured the artifacts and gated nothing, so a base module
grown past Play's cap failed at upload -- after the GitHub release was already
public. One version then ships through two channels with different contents:
sideloaders get the new APK and toolchain ZIPs, Play still serves the previous
version.

What this measures, and why it is safe in the direction that matters
--------------------------------------------------------------------
Play's limit applies to the compressed download for one device configuration.
A bundle carries every configuration, so a per-module total taken from it is
greater than or equal to any single device's download. This check can therefore
be stricter than Play but never more permissive: a bundle it passes cannot be
one Play rejects for module size. It does not reproduce Play's own number and
does not try to.

What it does not cover -- stated rather than implied
----------------------------------------------------
Delivery mode (install-time, fast-follow, on-demand) lives in each module's
binary AndroidManifest and BundleConfig.pb, neither of which is read here. So:

  * the 4 GB cumulative cap on modules plus install-time packs is NOT checked,
    because which packs are install-time is not visible without parsing those;
  * the 30 GB on-demand budget is NOT checked and cannot be -- it is
    account-wide, not a property of one bundle.

Both are printed as uncovered every run. A gate that lists only what it checked
reads as though it checked everything, which is the failure this repository has
already shipped once.
"""

import pathlib
import sys
import zipfile

MIB = 1024 * 1024

# support.google.com/googleplay/android-developer/answer/9859372
BASE_CAP_MIB = 500
PACK_CAP_MIB = 1536

# Not modules: bundle-level metadata and the config protobuf.
NOT_A_MODULE = {"BUNDLE-METADATA", "META-INF", "BundleConfig.pb"}


def module_sizes(aab):
    """Compressed and uncompressed bytes per top-level module."""
    sizes = {}
    with zipfile.ZipFile(aab) as z:
        for info in z.infolist():
            if info.is_dir():
                continue
            module = info.filename.split("/")[0]
            if module in NOT_A_MODULE:
                continue
            comp, raw = sizes.get(module, (0, 0))
            sizes[module] = (comp + info.compress_size, raw + info.file_size)
    return sizes


def main(argv) -> int:
    if len(argv) != 2:
        print("usage: check-bundle-size.py <app.aab>", file=sys.stderr)
        return 2
    aab = pathlib.Path(argv[1])
    if not aab.is_file():
        print(f"  FAIL   {aab} is not a file", file=sys.stderr)
        return 1

    sizes = module_sizes(aab)
    # An empty result would pass every comparison below. That is the shape this
    # gate exists to remove, so it is fatal rather than a silent success.
    if not sizes:
        print(f"  FAIL   no modules found in {aab.name}; the bundle layout changed")
        return 1

    failed = False
    for module, (comp, raw) in sorted(sizes.items(), key=lambda kv: -kv[1][0]):
        cap = BASE_CAP_MIB if module == "base" else PACK_CAP_MIB
        over = comp / MIB > cap
        status = "FAIL  " if over else "ok    "
        print(f"  {status} {module:<18} {comp / MIB:8.1f} MiB compressed "
              f"({raw / MIB:7.1f} MiB raw)  cap {cap} MiB")
        if over:
            print(f"         Play refuses this at upload. Reduce {module} or move "
                  f"content into an on-demand pack.")
            failed = True

    print()
    print("  not covered by this check:")
    print("    - the 4 GB cumulative cap on modules plus install-time packs")
    print("      (delivery mode is not readable without parsing binary manifests)")
    print("    - the 30 GB on-demand pack budget (account-wide, not in a bundle)")

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
