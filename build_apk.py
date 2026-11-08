import os
import subprocess
import shutil
import zipfile
import glob

# Try locating Android SDK automatically
SDK_DIR = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or os.path.expandvars(r"%LOCALAPPDATA%\Android\sdk")

if not os.path.exists(SDK_DIR):
    raise RuntimeError(f"Android SDK not found at {SDK_DIR}. Please set ANDROID_HOME environment variable.")

# Find latest build-tools and platform
build_tools_dir = os.path.join(SDK_DIR, "build-tools")
available_bt = sorted(os.listdir(build_tools_dir), reverse=True)
if not available_bt:
    raise RuntimeError("No build-tools found in Android SDK")
BUILD_TOOLS = os.path.join(build_tools_dir, available_bt[0])

platforms_dir = os.path.join(SDK_DIR, "platforms")
available_platforms = sorted([p for p in os.listdir(platforms_dir) if p.startswith("android-")], reverse=True)
if not available_platforms:
    raise RuntimeError("No platforms found in Android SDK")
PLATFORM_JAR = os.path.join(platforms_dir, available_platforms[0], "android.jar")

D8_JAR = os.path.join(BUILD_TOOLS, "lib", "d8.jar")
AAPT = os.path.join(BUILD_TOOLS, "aapt.exe" if os.name == "nt" else "aapt")
ZIPALIGN = os.path.join(BUILD_TOOLS, "zipalign.exe" if os.name == "nt" else "zipalign")
APKSIGNER = os.path.join(BUILD_TOOLS, "apksigner.bat" if os.name == "nt" else "apksigner")

def find_keytool():
    k = shutil.which("keytool")
    if k:
        return k
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        cand = os.path.join(java_home, "bin", "keytool.exe" if os.name == "nt" else "keytool")
        if os.path.exists(cand):
            return cand
    for p in [r"C:\Program Files\Java", r"C:\Program Files (x86)\Java"]:
        if os.path.exists(p):
            for root_dir, _, files in os.walk(p):
                if "keytool.exe" in files:
                    return os.path.join(root_dir, "keytool.exe")
    return "keytool"

def run(cmd, desc):
    print(f"[*] {desc}...")
    res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, errors="replace")
    if res.returncode != 0:
        print(f"[!] Error in {desc}:")
        print("STDOUT:", res.stdout)
        print("STDERR:", res.stderr)
        raise RuntimeError(f"Step failed: {desc}")
    return res

def main():
    root = os.path.dirname(os.path.abspath(__file__))
    app_dir = os.path.join(root, "app", "src", "main")
    java_src = os.path.join(app_dir, "java", "com", "freechat", "app", "MainActivity.java")
    res_dir = os.path.join(app_dir, "res")
    manifest = os.path.join(app_dir, "AndroidManifest.xml")

    build_dir = os.path.join(root, "build")
    if os.path.exists(build_dir):
        shutil.rmtree(build_dir)
    os.makedirs(build_dir, exist_ok=True)

    # 1. Generate R.java with AAPT
    r_dir = os.path.join(build_dir, "gen")
    os.makedirs(r_dir, exist_ok=True)
    run([
        AAPT, "package", "-f", "-m",
        "-J", r_dir,
        "-M", manifest,
        "-S", res_dir,
        "-I", PLATFORM_JAR
    ], "Generating R.java")

    r_java = os.path.join(r_dir, "com", "freechat", "app", "R.java")

    # 2. Compile Java sources
    classes_dir = os.path.join(build_dir, "classes")
    os.makedirs(classes_dir, exist_ok=True)
    run([
        "javac", "-cp", PLATFORM_JAR,
        "--release", "8", "-d", classes_dir,
        java_src, r_java
    ], "Compiling Java sources")

    # 3. Dex with D8
    class_files = glob.glob(os.path.join(classes_dir, "**", "*.class"), recursive=True)
    run([
        "java", "-cp", D8_JAR,
        "com.android.tools.r8.D8", "--release", "--min-api", "21",
        "--lib", PLATFORM_JAR,
        "--output", build_dir
    ] + class_files, "Running D8 dexer")

    dex_file = os.path.join(build_dir, "classes.dex")
    assert os.path.exists(dex_file), "classes.dex not generated"

    # 4. Package resources into base APK
    base_apk = os.path.join(build_dir, "base.apk")
    run([
        AAPT, "package", "-f", "-m",
        "-M", manifest,
        "-S", res_dir,
        "-I", PLATFORM_JAR,
        "-F", base_apk
    ], "Packaging resources with AAPT")

    # 5. Add classes.dex
    unaligned_apk = os.path.join(build_dir, "unaligned.apk")
    shutil.copyfile(base_apk, unaligned_apk)
    with zipfile.ZipFile(unaligned_apk, 'a') as zf:
        zf.write(dex_file, "classes.dex")

    # 6. Zipalign
    aligned_apk = os.path.join(build_dir, "aligned.apk")
    run([
        ZIPALIGN, "-f", "-p", "4",
        unaligned_apk, aligned_apk
    ], "Aligning APK")

    # 7. Keystore (Persistent release keystore to allow seamless app updates without signature conflicts)
    keystore = os.path.join(root, "freechat.keystore")
    if not os.path.exists(keystore):
        keytool = find_keytool()
        run([
            keytool, "-genkeypair", "-v",
            "-keystore", keystore,
            "-alias", "freechatkey",
            "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "10000",
            "-storepass", "freechatpass", "-keypass", "freechatpass",
            "-dname", "CN=FreeChat,O=OpenRouter,C=US"
        ], "Generating persistent release keystore")

    # 8. Sign APK
    final_apk = os.path.join(root, "FreeChat.apk")
    if os.path.exists(final_apk):
        os.remove(final_apk)
    shutil.copyfile(aligned_apk, final_apk)

    run([
        APKSIGNER, "sign",
        "--ks", keystore,
        "--ks-pass", "pass:freechatpass",
        "--ks-key-alias", "freechatkey",
        "--key-pass", "pass:freechatpass",
        final_apk
    ], "Signing APK")

    print("\n" + "=" * 55)
    print(f"SUCCESS! Output APK: {final_apk}")
    print(f"APK Size: {os.path.getsize(final_apk)} bytes")
    print("=" * 55)

if __name__ == "__main__":
    main()
