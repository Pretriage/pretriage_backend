#!/usr/bin/env python3
import re
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODEL_DIR = ROOT / "src" / "main" / "java" / "com" / "pretriage" / "backend" / "model"
OUT_DIR = ROOT / "docs" / "generated"
OUT_MMD = OUT_DIR / "domain-model.mmd"
OUT_MD = OUT_DIR / "domain-model.md"

RELATION_ANNOTATIONS = {
    "OneToOne": "||--||",
    "ManyToOne": "}o--||",
    "OneToMany": "||--o{",
    "ManyToMany": "}o--o{",
}

FIELD_PATTERN = re.compile(
    r"(?:private|protected|public)\s+(?:final\s+)?(?P<type>[A-Za-z0-9_<>, ?]+)\s+(?P<name>[A-Za-z0-9_]+)\s*(?:=|;)",
    re.MULTILINE,
)
CLASS_PATTERN = re.compile(r"\b(?:class|enum)\s+(?P<name>[A-Za-z0-9_]+)")
PACKAGE_PATTERN = re.compile(r"package\s+(?P<package>[A-Za-z0-9_.]+);")


@dataclass
class FieldInfo:
    name: str
    type_name: str
    annotations: list[str] = field(default_factory=list)


@dataclass
class EntityInfo:
    name: str
    package: str
    path: Path
    fields: list[FieldInfo] = field(default_factory=list)


def strip_comments(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
    text = re.sub(r"//.*", "", text)
    return text


def simple_type(type_name: str) -> str:
    cleaned = type_name.strip()
    cleaned = cleaned.replace("? extends ", "").replace("? super ", "")
    if "<" in cleaned and ">" in cleaned:
        inner = cleaned[cleaned.find("<") + 1: cleaned.rfind(">")]
        return simple_type(inner.split(",")[-1])
    return cleaned.split(".")[-1].strip()


def mermaid_name(name: str) -> str:
    chars = []
    for index, char in enumerate(name):
        if char.isupper() and index > 0 and (not name[index - 1].isupper()):
            chars.append("_")
        chars.append(char.upper())
    return "".join(chars)


def read_entities() -> dict[str, EntityInfo]:
    entities = {}
    for path in sorted(MODEL_DIR.rglob("*.java")):
        text = strip_comments(path.read_text(encoding="utf-8-sig"))
        if "@Entity" not in text:
            continue
        class_match = CLASS_PATTERN.search(text)
        package_match = PACKAGE_PATTERN.search(text)
        if not class_match or not package_match:
            continue
        entity = EntityInfo(
            name=class_match.group("name"),
            package=package_match.group("package"),
            path=path,
        )
        entity.fields = parse_fields(text)
        entities[entity.name] = entity
    return entities


def parse_fields(text: str) -> list[FieldInfo]:
    lines = text.splitlines()
    fields = []
    pending_annotations = []
    field_buffer = []

    for raw_line in lines:
        line = raw_line.strip()
        if not line:
            if not pending_annotations:
                field_buffer = []
            continue
        if line.startswith("@"):
            annotation = line[1:].split("(", 1)[0].strip()
            pending_annotations.append(annotation)
            continue
        if any(line.startswith(prefix) for prefix in ("private ", "protected ", "public ")):
            field_buffer = [line]
            if ";" not in line and "=" not in line:
                continue
        elif field_buffer:
            field_buffer.append(line)
        else:
            if not line.startswith("@"):
                pending_annotations = []
            continue

        candidate = " ".join(field_buffer)
        if ";" in candidate or "=" in candidate:
            match = FIELD_PATTERN.search(candidate)
            if match:
                fields.append(FieldInfo(
                    name=match.group("name"),
                    type_name=match.group("type").strip(),
                    annotations=pending_annotations,
                ))
            pending_annotations = []
            field_buffer = []
    return fields


def relation_label(field: FieldInfo) -> str:
    return field.name


def build_mermaid(entities: dict[str, EntityInfo]) -> str:
    lines = ["erDiagram"]
    entity_names = set(entities.keys())
    emitted_relations = set()

    for entity in entities.values():
        lines.append(f"    {mermaid_name(entity.name)} {{")
        for field in entity.fields:
            if any(annotation in RELATION_ANNOTATIONS for annotation in field.annotations):
                continue
            field_type = simple_type(field.type_name)
            if field_type in entity_names:
                continue
            lines.append(f"        {sanitize_mermaid_type(field_type)} {field.name}")
        lines.append("    }")

    for entity in entities.values():
        source = mermaid_name(entity.name)
        for field in entity.fields:
            annotation = next((ann for ann in field.annotations if ann in RELATION_ANNOTATIONS), None)
            if annotation is None:
                continue
            target_type = simple_type(field.type_name)
            if target_type not in entity_names:
                continue
            target = mermaid_name(target_type)
            connector = RELATION_ANNOTATIONS[annotation]
            label = relation_label(field)
            key = (source, connector, target, label)
            if key in emitted_relations:
                continue
            emitted_relations.add(key)
            lines.append(f"    {source} {connector} {target} : {label}")

    return "\n".join(lines) + "\n"


def sanitize_mermaid_type(type_name: str) -> str:
    type_name = re.sub(r"[^A-Za-z0-9_]", "_", type_name)
    if not type_name:
        return "String"
    return type_name


def build_markdown(entities: dict[str, EntityInfo], mermaid: str) -> str:
    entity_list = "\n".join(
        f"- `{name}`: `{entity.path.relative_to(ROOT).as_posix()}`"
        for name, entity in sorted(entities.items())
    )
    return f"""# Generated Domain Model

This file is generated from JPA entity classes under:

```text
src/main/java/com/pretriage/backend/model
```

Regenerate with:

```powershell
python scripts\\generate_domain_diagram.py
```

## Entities

{entity_list}

## Mermaid ER Diagram

```mermaid
{mermaid}```
"""


def main():
    entities = read_entities()
    if not entities:
        raise RuntimeError(f"No JPA entities found under {MODEL_DIR}")
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    mermaid = build_mermaid(entities)
    OUT_MMD.write_text(mermaid, encoding="utf-8")
    OUT_MD.write_text(build_markdown(entities, mermaid), encoding="utf-8")
    print(f"Generated {OUT_MMD.relative_to(ROOT)}")
    print(f"Generated {OUT_MD.relative_to(ROOT)}")
    print(f"Entities: {len(entities)}")


if __name__ == "__main__":
    main()
