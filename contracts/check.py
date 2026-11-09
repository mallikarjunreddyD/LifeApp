"""Validate the OpenAPI document and cross-client JSON fixtures."""
import argparse
import json
from pathlib import Path

import yaml
from jsonschema import Draft202012Validator, FormatChecker
from openapi_spec_validator import validate

ROOT = Path(__file__).resolve().parent
contract = yaml.safe_load((ROOT / "openapi/lifeapp-v1.yaml").read_text())
validate(contract)


def schema(name):
    return Draft202012Validator(
        {"$ref": f"#/components/schemas/{name}", "components": contract["components"]},
        format_checker=FormatChecker(),
    )


fixtures = {}
for name, model in {
    "todo-operation": "TodoOperation",
    "sync-page": "SyncPage",
    "error": "Error",
    "workout": "Workout",
}.items():
    fixtures[name] = json.loads((ROOT / f"fixtures/{name}.json").read_text())
    schema(model).validate(fixtures[name])

operation = fixtures["todo-operation"]
assert fixtures["sync-page"]["entries"][0]["operation"] == operation
assert int(operation["sequence"]) > 2**53
assert not schema("TodoOperation").is_valid(dict(operation, sequence=int(operation["sequence"])))
assert not schema("TodoOperation").is_valid(dict(operation, protocolVersion=2))
assert not schema("TodoOperation").is_valid(dict(operation, payloadVersion=2))
assert not schema("Error").is_valid(dict(fixtures["error"], code="UNKNOWN"))
assert contract["security"] == [{"browserSession": []}, {"androidBearer": []}]
public = {("get", "/health"), ("get", "/ready"), ("post", "/v1/session")}
for path, methods in contract["paths"].items():
    for method, operation in methods.items():
        assert (operation.get("security") == []) == ((method, path) in public)
        for code, response in operation["responses"].items():
            if int(code) >= 400:
                response = contract["components"]["responses"][response["$ref"].split("/")[-1]]
                assert response["content"]["application/json"]["schema"]["$ref"] == "#/components/schemas/Error"
print("OpenAPI, four portable fixtures, authentication and error responses verified.")

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--responses", type=Path)
args = parser.parse_args()
if args.responses:
    for model in ("Acknowledgment", "SyncPage", "Error", "Workout"):
        sample = json.loads((args.responses / f"{model}.json").read_text())
        schema(model).validate(sample)
    print("Recorded Ktor/PostgreSQL responses match OpenAPI schemas.")
