"""Independent ADR-0021 fixture for the two Java golden vectors."""

from hashlib import sha256
from struct import pack


def text(value):
    encoded = value.encode("utf-8")
    return pack(">I", len(encoded)) + encoded


profile = "postgresql-17-catalog-v1"
snapshot = text("TUPREL-PG-CATALOG-SNAPSHOT") + pack(">I", 1)
snapshot += text(profile) + text("fixture") + pack(">I", 1)
snapshot += text("Example") + pack(">I", 3)
snapshot += text("Id") + text("int4") + pack(">??", False, True)
snapshot += text("label") + text("text") + pack(">??", True, False)
snapshot += text("rank") + text("int8") + pack(">??", False, False)
snapshot += pack(">I", 1) + text("lookup") + pack(">I", 2) + text("rank") + text("label")
structural = sha256(snapshot).hexdigest()

outer = text("TUPREL-PG-PHYSICAL-DEPLOY") + pack(">I", 1) + text("a" * 64)
outer += pack(">QQ", 4294967294, 987654321) + text(profile) + text("b" * 64)
outer += text(structural) + text(structural) + pack(">I", 5)
for name, oid in [("Example", 710), ("Example_pkey", 711), ("_tuprel_migrations", 712),
                  ("_tuprel_migrations_pkey", 713), ("lookup", 714)]:
    outer += text(name) + pack(">Q", oid)

print(structural)
print(sha256(outer).hexdigest())
