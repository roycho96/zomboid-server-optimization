import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent
ASM_URL = "https://repo.maven.apache.org/maven2/org/ow2/asm"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(args):
    result = subprocess.run([str(arg) for arg in args], text=True, capture_output=True, timeout=240)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout


def pack(output, classes):
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(classes.rglob("*.class")):
            info = zipfile.ZipInfo(path.relative_to(classes).as_posix(), (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, path.read_bytes())


def build(game, jdk):
    manifest = json.loads((ROOT / "manifest.json").read_text())
    stock = game / "projectzomboid.jar"
    if not stock.is_file() or sha(stock) != manifest["game_jar_sha256"]:
        raise RuntimeError("Unsupported game jar; this release supports the recorded 42.21.0 build")
    optimizer = game / "pzopt/aot/pzopt.jar"
    if optimizer.exists() and sha(optimizer) not in manifest["known_optimizer_sha256"]:
        raise RuntimeError("Inspect the changed PZ Optimizer before building")
    suffix = ".exe" if os.name == "nt" else ""
    javac = jdk / ("javac" + suffix)
    java = jdk / ("java" + suffix)
    if not run([javac, "-version"]).startswith("javac 25."):
        raise RuntimeError("Use JDK 25; this release was built with Temurin 25.0.5+8")
    libraries = []
    for name in ("asm", "asm-tree"):
        path = ROOT / f"tools/{name}-9.9.1.jar"
        expected = manifest["files"][path.relative_to(ROOT).as_posix()]
        if not path.exists():
            path.parent.mkdir(exist_ok=True)
            with urllib.request.urlopen(f"{ASM_URL}/{name}/9.9.1/{name}-9.9.1.jar", timeout=30) as response:
                content = response.read()
            if hashlib.sha256(content).hexdigest() != expected:
                raise RuntimeError("Downloaded ASM hash differs")
            path.write_bytes(content)
        if sha(path) != expected:
            raise RuntimeError("ASM hash differs: " + name)
        libraries.append(path)
    output = ROOT / "build"
    payload = ROOT / "payload"
    output.mkdir(exist_ok=True)
    payload.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="compile-", dir=output) as temporary:
        temporary = Path(temporary)
        helpers, recipes = temporary / "helpers", temporary / "recipe"
        helpers.mkdir()
        recipes.mkdir()
        run([javac, "-Xlint:all", "-cp", stock, "-d", helpers, *sorted((ROOT / "src").rglob("*.java"))])
        run([javac, "-Xlint:all", "-cp", os.pathsep.join(map(str, [*libraries, stock])),
             "-d", recipes, *sorted((ROOT / "build-src").glob("*.java"))])
        helper_jar = payload / f"roy-server-helpers-{manifest['version']}.jar"
        recipe_jar = payload / f"roy-server-recipe-{manifest['version']}.jar"
        pack(helper_jar, helpers)
        pack(recipe_jar, recipes)
    for path in (helper_jar, recipe_jar):
        if sha(path) != manifest["files"][path.relative_to(ROOT).as_posix()]:
            raise RuntimeError("Compiler output differs from the release; use the recorded JDK version")
    cp = os.pathsep.join(map(str, [recipe_jar, *libraries, stock]))
    base = [java, "-Xverify:all", "-cp", cp]
    patched = json.loads(run([*base, "RoyServerRecipe", "--hashes", stock]))
    if patched != manifest["generated_classes"]:
        raise RuntimeError("Generated game class hashes differ")
    artifact = output / f"roy-server-opt-{manifest['version']}.jar"
    generated = json.loads(run([*base, "RoyServerRecipe", stock, helper_jar, artifact,
        manifest["game_jar_sha256"], *manifest["source_classes"].values(), *patched.values()]))
    if generated["artifact_sha256"] != manifest["generated_artifact_sha256"]:
        raise RuntimeError("Generated local artifact differs")
    contracts = {}
    for name in ("VerifyServerVocalsSources", "VerifyNativeMembershipSources",
                 "VerifyZombieRequestSources", "VerifyServerDirectionSources", "VerifyClasspathSources"):
        args = [*base, name, stock, optimizer if optimizer.exists() else "-"]
        if name in ("VerifyZombieRequestSources", "VerifyClasspathSources"):
            args.append(game)
        contracts[name] = json.loads(run(args))
        if contracts[name].get("verified") is not True:
            raise RuntimeError("Source contract failed: " + name)
    runtime_cp = [recipe_jar, artifact]
    if optimizer.exists():
        runtime_cp.append(optimizer)
    runtime_cp.append(stock)
    runtime = json.loads(run([java, "-Xverify:all", "-cp", os.pathsep.join(map(str, runtime_cp)),
                              "VerifyServerPatch", artifact, stock]))
    if runtime.get("verified") is not True:
        raise RuntimeError("Runtime verification failed")
    report = {"passed": True, "artifact_sha256": sha(artifact), "contracts": contracts, "runtime": runtime}
    (output / "verification.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"passed": True, "version": manifest["version"], "artifact_sha256": sha(artifact),
                      "source_contracts": len(contracts), "game_classes_kept_in_ignored_build_directory": True}, indent=2))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--game-dir", type=Path, required=True)
    parser.add_argument("--jdk", type=Path, help="JDK directory containing bin/")
    args = parser.parse_args()
    if args.jdk:
        compiler_dir = args.jdk.resolve() / "bin"
    elif os.environ.get("JAVA_HOME"):
        compiler_dir = Path(os.environ["JAVA_HOME"]).resolve() / "bin"
    else:
        compiler = shutil.which("javac")
        if not compiler:
            raise RuntimeError("Pass --jdk or configure JAVA_HOME")
        compiler_dir = Path(compiler).resolve().parent
    build(args.game_dir.resolve(), compiler_dir)


if __name__ == "__main__":
    main()