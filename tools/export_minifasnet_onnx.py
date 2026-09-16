#!/usr/bin/env python3
"""Export the pinned Silent-Face-Anti-Spoofing checkpoints to ONNX.

Run this from a checkout of the upstream repository. The resulting files are
deterministic inference artifacts except for ONNX serializer metadata.
"""

import argparse
from pathlib import Path
import sys

import torch


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("upstream", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    sys.path.insert(0, str(args.upstream))

    from src.anti_spoof_predict import MODEL_MAPPING
    from src.utility import get_kernel, parse_model_name

    args.output.mkdir(parents=True, exist_ok=True)
    model_dir = args.upstream / "resources" / "anti_spoof_models"
    for checkpoint in sorted(model_dir.glob("*.pth")):
        height, width, model_type, _ = parse_model_name(checkpoint.name)
        model = MODEL_MAPPING[model_type](conv6_kernel=get_kernel(height, width))
        state = torch.load(checkpoint, map_location="cpu", weights_only=True)
        if next(iter(state)).startswith("module."):
            state = {key.removeprefix("module."): value for key, value in state.items()}
        model.load_state_dict(state)
        model.eval()
        destination = args.output / f"{checkpoint.stem}.onnx"
        torch.onnx.export(
            model,
            torch.zeros(1, 3, height, width),
            destination,
            input_names=["input"],
            output_names=["logits"],
            opset_version=17,
            do_constant_folding=True,
            dynamo=False,
        )
        print(destination)


if __name__ == "__main__":
    main()
