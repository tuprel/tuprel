package dev.tuprel.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuprel.schema.ast.SchemaDocument.Cardinality;
import dev.tuprel.schema.model.ValidatedSchema.TypeKind;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SchemaSymbolsTest {
    private final SchemaSymbols symbols = new SchemaSymbols();

    @Test
    void resolvesTheUseToTheExactForwardDeclarationIgnoringNamesInTrivia() {
        String text = """
                // model Customer {}
                model Order {
                    id UUID @id
                    customer /* fake Customer */ Customer?
                    status Status
                }
                model /* 🧪 */ Customer { id UUID @id name String }
                enum Status { New Paid }
                """.replace("\n", "\r\n");
        SourceText source = SourceText.of("schema.tuprel", text);
        int use = text.indexOf("Customer?");
        int target = text.indexOf("Customer { id");
        SchemaSymbols.Match reference = symbols.lookup(source, use + 4).orElseThrow();
        assertEquals(source.span(use, use + 8), reference.occurrence());
        SourceSpan definition = reference.definition().orElseThrow();
        assertEquals(source.span(target, target + 8), definition);
        assertSame(source, definition.source());
        SchemaSymbols.Model model = assertInstanceOf(SchemaSymbols.Model.class, reference.symbol());
        assertEquals("Customer", model.name());
        assertEquals(List.of("id", "name"), model.fields().stream().map(SchemaSymbols.Field::name).toList());
        assertEquals(model, symbols.lookup(source, target).orElseThrow().symbol());
        assertTrue(symbols.lookup(source, target + 8).isEmpty());
        assertTrue(symbols.lookup(source, text.indexOf("Customer")).isEmpty());

        int enumUse = text.indexOf("Status");
        int enumTarget = text.lastIndexOf("Status");
        SchemaSymbols.Match enumMatch = symbols.lookup(source, enumUse).orElseThrow();
        assertEquals(source.span(enumUse, enumUse + 6), enumMatch.occurrence());
        assertEquals(source.span(enumTarget, enumTarget + 6), enumMatch.definition().orElseThrow());
        assertEquals(new SchemaSymbols.Enumeration("Status", List.of("New", "Paid")), enumMatch.symbol());
    }

    @Test
    void distinguishesSameNamedFieldsByModelAndReturnsCardinalityAndOfficialType() {
        SchemaSymbols.Match first = lookup("model A { f|ield String? } model B { field Status[] } enum Status { Ready }")
                .orElseThrow();
        SchemaSymbols.Field scalarField = assertInstanceOf(SchemaSymbols.Field.class, first.symbol());
        assertEquals("A", scalarField.model());
        assertEquals("String", scalarField.type().name());
        assertEquals(TypeKind.SCALAR, scalarField.type().kind());
        assertEquals(Cardinality.OPTIONAL, scalarField.cardinality());
        assertEquals("field", first.occurrence().text());
        assertTrue(first.definition().isEmpty());

        SchemaSymbols.Field enumField = assertInstanceOf(SchemaSymbols.Field.class,
                lookup("model A { field String? } model B { f|ield Status[] } enum Status { Ready }")
                        .orElseThrow().symbol());
        assertEquals("B", enumField.model());
        assertEquals(TypeKind.ENUM, enumField.type().kind());
        assertEquals(Cardinality.LIST, enumField.cardinality());
        SchemaSymbols.Match scalar = lookup("model A { id U|UID @id }").orElseThrow();
        assertEquals(new SchemaSymbols.Scalar("UUID"), scalar.symbol());
        assertTrue(scalar.definition().isEmpty());
        assertEquals(new SchemaSymbols.Scalar("String"),
                lookup("model A { field S|tring } model String { id Int @id }").orElseThrow().symbol());
    }

    @Test
    void preservesCertainBindingsDespiteIndependentSyntaxAndSemanticErrors() {
        String text = "model Broken { incomplete }\nmodel Use { field C|ustomer? }\n"
                + "model Customer { unknown Unknown }";
        SchemaSymbols.Match recovered = lookup(text).orElseThrow();
        assertEquals("Customer", recovered.definition().orElseThrow().text());
        assertEquals(new SchemaSymbols.Model("Customer", List.of()), recovered.symbol());

        assertEquals("Ready", assertInstanceOf(SchemaSymbols.Enumeration.class,
                lookup("model A { state S|tatus }\nenum Status { Ready").orElseThrow().symbol()).values().getFirst());
        assertFalse(lookup("model A { id U|UID @id").isEmpty());
    }

    @Test
    void rejectsAmbiguousUnknownAndRecoveryDependentBindings() {
        for (String marked : List.of(
                "model A { value T|arget } model Target {} enum Target { Ready }",
                "model A { value T|arget } model Target {} model Target",
                "model T|arget {} model Target {}",
                "model A { f|ield Int field String }",
                "model A { field I|nt field String }",
                "model A { target U|nknown }",
                "model A { t|arget Unknown }",
                "model A { value T|arget } model Target",
                "model A { value T|arget } { model Target {} }",
                "model A { value T|arget } model Target $ {}",
                "model A { x| UUID[ }",
                "model A { x $ U|UID }",
                "model A { id Int @default(foo(a U|UID)) }",
                "model A { id Int @relation(fields: [i|d], references: [id]) }",
                "model cl|ass {}",
                "enum Status { R|eady }",
                "model |A model B {}")) {
            assertTrue(lookup(marked).isEmpty(), marked);
        }
    }

    @Test
    void declinesCommentsStringsAndOffsetsOutsideAnIdentifier() {
        for (String marked : List.of("// C|ustomer", "/* 🧪 C|ustomer */",
                "model A { text String @default(\"C|ustomer\") }",
                "datasource db { url = \"secret|", "model A { id UUID @id }|")) {
            assertTrue(lookup(marked).isEmpty(), marked);
        }
        SourceText source = SourceText.of("schema.tuprel", "model A { id Int @id }");
        assertTrue(symbols.lookup(source, -1).isEmpty());
        assertTrue(symbols.lookup(source, source.length() + 1).isEmpty());
    }

    private Optional<SchemaSymbols.Match> lookup(String marked) {
        return symbols.lookup(SourceText.of("schema.tuprel", marked.replace("|", "")), marked.indexOf('|'));
    }
}
