"""Paths shared by the estimate check scripts (override with environment variables)."""
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
# Where models, slices and results go.
WORK = os.environ.get("FSV_CHECK_DIR", os.path.join(ROOT, "build", "estimate-check"))
MODELS = os.path.join(WORK, "models")
RUNS = os.path.join(WORK, "runs")
RESULTS = os.path.join(WORK, "results.jsonl")
# Bambu Studio's bundled profiles (the Flathub install by default).
PROFILES = os.environ.get(
    "BAMBU_PROFILES",
    "/var/lib/flatpak/app/com.bambulab.BambuStudio/x86_64/stable/active/files/share/BambuStudio/profiles",
)
# The desktop build of the estimator (cmake -S native -B native/build-host && ninja -C native/build-host).
ESTIMATE = os.environ.get("FSV_ESTIMATE", os.path.join(ROOT, "native", "build-host", "fsv_estimate"))
SAMPLES = os.path.join(ROOT, "app", "src", "main", "assets", "samples")
