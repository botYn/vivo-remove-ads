#!/usr/bin/env python3
"""APK 广告组件剥离工具。

基于 apktool 解包 → 识别广告组件 → 删除 → 回包 → 可选自动签名。
仅用于研究学习场景，请仅对自己拥有或已获授权的 APK 使用。

用法:
    python apk_ad_strip.py --list  app.apk
    python apk_ad_strip.py --strip app.apk -o out/
    python apk_ad_strip.py --strip app.apk -o out/ --sign
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, List, Sequence

# --------------------------------------------------------------------------------------
# 识别规则
# --------------------------------------------------------------------------------------

#: 广告 SDK 的包名前缀（命中即整包判定为广告组件）
AD_PACKAGE_PREFIXES: Sequence[str] = (
    "com.qq.e.ads",
    "com.qq.e.union.ads",
    "com.tencent.ad",
    "com.tencent.tads",
    "com.bytedance.sdk.openadsdk",
    "com.bytedance.sdk.tp",
    "com.sigmob",
    "com.baidu.mobads",
    "com.baidu.mobad",
    "com.baidu.ad",
    "com.kwad.sdk",
    "com.kuaishou.ad",
    "com.qq.e.comm",
    "com.google.android.gms.ads",
    "com.google.ads",
    "com.facebook.ads",
    "com.facebook.ads.",
    "com.mintegral",
    "com.mbridge",
    "com.mobvista",
    "com.smaato",
    "com.inmobi",
    "com.startapp",
    "com.applovin",
    "com.ironsource",
    "com.vungle",
    "com.chartboost",
    "com.huawei.hwid",
    "com.hihonor.ad",
    "com.vivo.ad",
    "com.bbk.ad",
    "com.meizu.ad",
    "com.oppo.ad",
    "com.xiaomi.ad",
    "com.heytap.mcc",
    "com.nearme.ad",
    "com.hapub",
)

#: 类名 / 资源名中包含即视为广告候选的关键词
AD_KEYWORDS: Sequence[str] = (
    "adview",
    "adbanner",
    "adslot",
    "adcontainer",
    "adviewholder",
    "adlayout",
    "adpopup",
    "adsplash",
    "advert",
    "advertising",
    "gdtad",
    "gdtmedia",
    "gdtbanner",
    "gdtinterstitial",
    "gdtnewsplash",
    "gdtnative",
    "gdtvideo",
    "unifiedbanner",
    "unifiedinterstitial",
    "unifiedsplash",
    "splashad",
    "splashscreenad",
    "nativead",
    "bannerad",
    "interstitialad",
    "rewardedad",
    "rewardvideo",
    "mediationad",
    "adrequest",
    "admanager",
    "adsdk",
)

#: 仅含这些词时视为「疑似」而非「确定」，避免误伤业务代码
AMBIGUOUS_KEYWORDS: Sequence[str] = ("ad", "ads")

#: 已知广告 SDK 的基类 / 接口全名
AD_BASE_CLASSES: Sequence[str] = (
    "com.google.android.gms.ads.AdView",
    "com.google.android.gms.ads.AdManager",
    "com.google.android.gms.ads.adloader.AdLoader",
    "com.qq.e.ads.adnet.adsdk.ADSdk",
    "com.baidu.mobads.AdView",
    "com.bytedance.sdk.openadsdk.stub.activity.StubActivity",
)

#: 广告常见的中文/英文资源名
AD_RES_NAME_PATTERN = re.compile(
    r"(^|_)(ad|ads|splash|gdt|banner|interstitial|reward|mediation)(_|\\.|$)",
    re.IGNORECASE,
)

#: AndroidManifest 中与广告强相关的节点/属性
MANIFEST_AD_TAGS = ("com.google.android.gms.ads", "AdActivity", "AdView")

# --------------------------------------------------------------------------------------
# 数据结构
# --------------------------------------------------------------------------------------


@dataclass
class AdHit:
    """一个被判定为广告组件的候选项。"""

    kind: str  # package | class | layout | manifest | baseclass
    target: str  # 包名 / 类名 / 文件路径
    reason: str  # 判定理由
    confidence: str = "high"  # high | medium

    def __str__(self) -> str:  # pragma: no cover - 展示用
        return f"[{self.confidence:<6}] {self.kind:<9} {self.target}\n           ↳ {self.reason}"


@dataclass
class StripResult:
    """一次剥离操作的结果汇总。"""

    apk: Path
    decoded_dir: Path
    hits: List[AdHit] = field(default_factory=list)
    removed: List[str] = field(default_factory=list)
    output_apk: Path | None = None
    signed: bool = False

    @property
    def high_confidence(self) -> List[AdHit]:
        return [h for h in self.hits if h.confidence == "high"]

    @property
    def medium_confidence(self) -> List[AdHit]:
        return [h for h in self.hits if h.confidence == "medium"]


# --------------------------------------------------------------------------------------
# 外部工具
# --------------------------------------------------------------------------------------


class ToolMissing(RuntimeError):
    """外部工具缺失。"""


def _which(name: str) -> str | None:
    return shutil.which(name)


def require_tool(name: str) -> str:
    path = _which(name)
    if not path:
        raise ToolMissing(
            f"未找到外部工具 `{name}`，请先安装并加入 PATH。\n"
            f"  apktool:  https://ibotpeaches.github.io/Apktool/\n"
            f"  apksigner/zipalign: Android SDK Build-Tools"
        )
    return path


def run(cmd: Sequence[str], cwd: Path | None = None, check: bool = True) -> subprocess.CompletedProcess:
    proc = subprocess.run(
        list(cmd),
        cwd=str(cwd) if cwd else None,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if check and proc.returncode != 0:
        raise RuntimeError(f"命令执行失败 ({proc.returncode}): {' '.join(cmd)}\n{proc.stdout}")
    return proc


# --------------------------------------------------------------------------------------
# 识别
# --------------------------------------------------------------------------------------


def match_ad_package(package: str) -> tuple[bool, str]:
    """按包名判定是否属于广告 SDK。返回 (命中, 理由)。"""
    low = package.lower()
    for prefix in AD_PACKAGE_PREFIXES:
        if low.startswith(prefix.lower()):
            return True, f"匹配广告 SDK 包前缀 `{prefix}`"
    return False, ""


def match_ad_class(class_name: str) -> tuple[bool, str, str]:
    """按类名/资源名判定。返回 (命中, 理由, 置信度)。"""
    simple = class_name.rsplit(".", 1)[-1].lower()
    full = class_name.lower()

    for base in AD_BASE_CLASSES:
        if full == base.lower() or full.startswith(base.lower() + "$"):
            return True, f"继承/实现已知广告基类 `{base}`", "high"

    for kw in AD_KEYWORDS:
        if kw in simple:
            return True, f"类名包含广告关键词 `{kw}`", "high"

    for kw in AMBIGUOUS_KEYWORDS:
        if kw in simple and len(simple) <= 12:
            return True, f"短类名包含弱关键词 `{kw}`（建议人工确认）", "medium"

    return False, "", ""


def scan_manifest(decoded: Path) -> List[AdHit]:
    hits: List[AdHit] = []
    manifest = decoded / "AndroidManifest.xml"
    if not manifest.exists():
        return hits

    text = manifest.read_text(encoding="utf-8", errors="replace")
    for tag in MANIFEST_AD_TAGS:
        if tag in text:
            hits.append(
                AdHit(
                    kind="manifest",
                    target="AndroidManifest.xml",
                    reason=f"清单中出现广告相关节点 `{tag}`",
                    confidence="high",
                )
            )
    for m in re.finditer(r'android:name="([^"]+)"', text):
        name = m.group(1)
        ok, reason = match_ad_package(name)
        if ok:
            hits.append(
                AdHit("manifest", name, reason, "high")
            )
    return hits


def scan_smali(decoded: Path) -> List[AdHit]:
    """扫描 smali 目录中的类。"""
    hits: List[AdHit] = []
    smali_root = decoded / "smali"
    if not smali_root.exists():
        return hits

    for dirpath, _dirnames, filenames in os.walk(smali_root):
        for fn in filenames:
            if not fn.endswith(".smali"):
                continue
            full = Path(dirpath) / fn
            rel = full.relative_to(smali_root).with_suffix("")
            class_name = ".".join(rel.parts)

            ok, reason = match_ad_package(class_name)
            if ok:
                hits.append(AdHit("package", class_name, reason, "high"))
                continue

            ok, reason, conf = match_ad_class(class_name)
            if ok:
                hits.append(AdHit("class", class_name, reason, conf))
                continue

            # 类内部引用了已知广告基类，也记一条线索
            try:
                content = full.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            for base in AD_BASE_CLASSES:
                if base in content:
                    hits.append(
                        AdHit(
                            "baseclass",
                            class_name,
                            f"代码中引用了广告基类 `{base}`",
                            "medium",
                        )
                    )
                    break
    return hits


def scan_layouts(decoded: Path) -> List[AdHit]:
    hits: List[AdHit] = []
    res_root = decoded / "res"
    if not res_root.exists():
        return hits

    for dirpath, _dirnames, filenames in os.walk(res_root):
        for fn in filenames:
            if not fn.endswith(".xml"):
                continue
            path = Path(dirpath) / fn
            rel = path.relative_to(decoded).as_posix()

            if AD_RES_NAME_PATTERN.search(fn):
                hits.append(
                    AdHit("layout", rel, f"资源文件名匹配广告命名模式", "medium")
                )
                continue

            try:
                content = path.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue

            for kw in ("AdView", "AdBanner", "AdSlot", "AdContainer", "GDTAD"):
                if kw in content:
                    hits.append(
                        AdHit("layout", rel, f"布局中引用广告控件 `{kw}`", "high")
                    )
                    break
    return hits


def scan_apk_contents(apk: Path) -> List[AdHit]:
    """不依赖 apktool，直接读 zip 目录做快速扫描。"""
    hits: List[AdHit] = []
    with zipfile.ZipFile(apk) as zf:
        for name in zf.namelist():
            if not name.endswith(".class") and not name.endswith(".dex"):
                continue
            # classes.dex 内部无法从文件名判断包名，跳过
            if name.endswith(".dex"):
                continue
            pkg = name.rsplit("/", 1)[0].replace("/", ".")
            ok, reason = match_ad_package(pkg)
            if ok:
                hits.append(AdHit("package", pkg, reason, "high"))
    return hits


# --------------------------------------------------------------------------------------
# 剥离
# --------------------------------------------------------------------------------------


def collect_removal_targets(hits: Iterable[AdHit], min_confidence: str = "high") -> List[str]:
    """把命中项映射为可删除的实际路径。"""
    order = {"high": 0, "medium": 1}
    threshold = order.get(min_confidence, 0)
    packages: set[str] = set()
    files: set[str] = set()

    for hit in hits:
        if order.get(hit.confidence, 1) > threshold:
            continue
        if hit.kind == "package":
            packages.add(hit.target)
        elif hit.kind in ("layout", "baseclass"):
            files.add(hit.target)

    targets: List[str] = []
    for pkg in sorted(packages):
        targets.append(f"smali*/{pkg.replace('.', '/')}")
    for f in sorted(files):
        targets.append(f)
    return targets


def remove_targets(decoded: Path, targets: Sequence[str]) -> List[str]:
    """删除目标；返回实际删除的条目列表。"""
    removed: List[str] = []

    for target in targets:
        if target.startswith("smali*/"):
            rel = target[len("smali*/"):]
            matched = False
            for smali_dir in decoded.glob("smali*"):
                candidate = smali_dir / rel
                if candidate.is_dir():
                    shutil.rmtree(candidate, ignore_errors=True)
                    removed.append(f"{target} (目录)")
                    matched = True
                elif candidate.is_file():
                    candidate.unlink(missing_ok=True)
                    removed.append(f"{target} (文件)")
                    matched = True
            if not matched:
                continue
        else:
            candidate = decoded / target
            if candidate.is_dir():
                shutil.rmtree(candidate, ignore_errors=True)
                removed.append(target)
            elif candidate.is_file():
                candidate.unlink(missing_ok=True)
                removed.append(target)
    return removed


def rebuild(decoded: Path, out_dir: Path, apk_name: str) -> Path:
    """用 apktool 回包。"""
    require_tool("apktool")
    out_dir.mkdir(parents=True, exist_ok=True)
    out_apk = out_dir / apk_name

    run(
        [
            "apktool",
            "b",
            str(decoded),
            "-o",
            str(out_apk),
            "-f",
        ]
    )
    return out_apk


def sign_apk(apk: Path, keystore: Path | None = None, ks_pass: str = "android") -> None:
    """对 APK 签名（默认使用 debug keystore）。"""
    require_tool("apksigner")
    require_tool("zipalign")

    if keystore is None:
        keystore = Path.home() / ".android" / "debug.keystore"
    if not keystore.exists():
        raise ToolMissing(
            f"未找到签名密钥 `{keystore}`。\n"
            "请用 keytool 生成：\n"
            "  keytool -genkey -v -keystore debug.keystore -storepass android "
            "-alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000"
        )

    aligned = apk.with_name(apk.stem + "-aligned.apk")
    run(["zipalign", "-f", "-p", "4", str(apk), str(aligned)])
    run(
        [
            "apksigner",
            "sign",
            "--ks",
            str(keystore),
            "--ks-pass",
            f"pass:{ks_pass}",
            "--key-pass",
            f"pass:{ks_pass}",
            "--ks-key-alias",
            "androiddebugkey",
            "--out",
            str(apk),
            str(aligned),
        ]
    )
    aligned.unlink(missing_ok=True)


# --------------------------------------------------------------------------------------
# 主流程
# --------------------------------------------------------------------------------------


def list_mode(apk: Path) -> int:
    hits = scan_apk_contents(apk)
    print(f"== zip 快速扫描: {apk.name} ==")
    for hit in hits:
        print(" ", hit)
    if not hits:
        print("  未在 zip 目录中直接发现广告包（需要 apktool 深度扫描才能看 smali 内部）")

    try:
        require_tool("apktool")
    except ToolMissing as exc:
        print(f"\n[提示] {exc}")
        return 0

    with tempfile.TemporaryDirectory(prefix="apk_ad_scan_") as tmp:
        decoded = Path(tmp) / "decoded"
        print(f"\n== apktool 解包中: {decoded} ==")
        run(["apktool", "d", "-f", str(apk), "-o", str(decoded)])
        hits = scan_manifest(decoded) + scan_smali(decoded) + scan_layouts(decoded)

        high = [h for h in hits if h.confidence == "high"]
        medium = [h for h in hits if h.confidence == "medium"]

        print(f"\n== 高置信度命中 ({len(high)}) ==")
        for hit in high:
            print(" ", hit)
        print(f"\n== 待确认命中 ({len(medium)}) ==")
        for hit in medium:
            print(" ", hit)
    return 0


def strip_mode(apk: Path, out_dir: Path, do_sign: bool, min_confidence: str) -> int:
    require_tool("apktool")

    with tempfile.TemporaryDirectory(prefix="apk_ad_strip_") as tmp:
        decoded = Path(tmp) / "decoded"
        print(f"[1/4] apktool 解包 {apk}")
        run(["apktool", "d", "-f", str(apk), "-o", str(decoded)])

        print("[2/4] 扫描广告组件")
        hits = scan_manifest(decoded) + scan_smali(decoded) + scan_layouts(decoded)
        targets = collect_removal_targets(hits, min_confidence)
        print(f"      命中 {len(hits)} 项，待移除目标 {len(targets)} 项")

        print("[3/4] 删除目标")
        removed = remove_targets(decoded, targets)
        for item in removed:
            print(f"      - {item}")

        print("[4/4] 回包")
        out_apk = rebuild(decoded, out_dir, apk.stem + "-noads.apk")
        print(f"      输出: {out_apk}")

        if do_sign:
            print("[+] 签名")
            try:
                sign_apk(out_apk)
                print(f"      已签名: {out_apk}")
            except ToolMissing as exc:
                print(f"      跳过签名: {exc}")

    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="APK 广告组件剥离工具（研究用途）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    p.add_argument("apk", nargs="?", type=Path, help="目标 APK 路径")
    mode = p.add_mutually_exclusive_group()
    mode.add_argument("--list", action="store_true", help="仅列出识别到的广告组件")
    mode.add_argument("--strip", action="store_true", help="剥离广告组件并重新打包")
    p.add_argument("-o", "--output", type=Path, default=Path("out"), help="输出目录（默认 out/）")
    p.add_argument("--sign", action="store_true", help="回包后用 debug keystore 自动签名")
    p.add_argument(
        "--confidence",
        choices=["high", "medium"],
        default="high",
        help="移除的最低置信度，默认 high（medium 会误伤更多业务代码）",
    )
    return p


def _configure_stdout() -> None:
    """Windows 终端默认 GBK，统一按 UTF-8 输出避免中文乱码。"""
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError, OSError):
            pass


def main(argv: Sequence[str] | None = None) -> int:
    _configure_stdout()
    args = build_parser().parse_args(argv)

    if not args.apk:
        print("缺少 APK 路径参数。用 --help 查看用法。", file=sys.stderr)
        return 2
    if not args.apk.is_file():
        print(f"文件不存在: {args.apk}", file=sys.stderr)
        return 2

    try:
        if args.list or not args.strip:
            return list_mode(args.apk)
        return strip_mode(args.apk, args.output, args.sign, args.confidence)
    except ToolMissing as exc:
        print(f"[错误] {exc}", file=sys.stderr)
        return 3
    except RuntimeError as exc:
        print(f"[错误] {exc}", file=sys.stderr)
        return 4


if __name__ == "__main__":
    raise SystemExit(main())
