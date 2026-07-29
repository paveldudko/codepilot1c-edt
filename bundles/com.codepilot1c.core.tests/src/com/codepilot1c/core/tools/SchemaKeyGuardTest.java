package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.Test;

/**
 * Tests for {@link SchemaKeyGuard} — the pure half of the silent-drop guard.
 *
 * <p>What was broken: a top-level key a tool's schema does not declare was discarded without a word.
 * Live, 2026-07-29: {@code add_metadata_child} called with {@code type} at the top level instead of
 * inside {@code properties} produced {@code valid:true} plus a token, and the mutation then wrote the
 * DEFAULT {@code String(150)} to the .mdo and reported success.</p>
 *
 * <p>These tests assert the guard's actual verdicts and message text, not the presence of any string
 * in the source. The fail-open cases are the regression guard that matters most: a schema that cannot
 * enumerate its keys must yield a clean report, because a false refusal on a legitimate call would be
 * worse than the bug being fixed.</p>
 */
public class SchemaKeyGuardTest {

    /** Shape of the real add_metadata_child schema, trimmed to what the guard reads. */
    private static final String ADD_CHILD_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {"type": "string", "description": "EDT project"},
                "parent_fqn": {"type": "string", "description": "FQN of the owner"},
                "child_kind": {"type": "string", "description": "Kind of the new child"},
                "name": {"type": "string", "description": "Name of the new child"},
                "template_type": {"type": "string", "description": "Template kind"},
                "properties": {
                  "type": "object",
                  "description": "Extra parameters. For attributes: type (e.g. String, Number), length, precision/scale, multiLine, fillChecking."
                },
                "validation_token": {"type": "string", "description": "One-time token"}
              },
              "required": ["project"]
            }
            """;

    private static final String FLAT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "projectName": {"type": "string", "description": "EDT project"},
                "scope": {"type": "string", "description": "Metadata scope filter"},
                "limit": {"type": "integer", "description": "Maximum entries"}
              }
            }
            """;

    // --- the proven live case ------------------------------------------------

    @Test
    public void aTopLevelTypeIsReportedAndPointedAtTheNestedPropertiesObject() {
        SchemaKeyGuard.Report report = SchemaKeyGuard.inspect(ADD_CHILD_SCHEMA,
                List.of("project", "parent_fqn", "child_kind", "name", "type"));

        assertTrue("the guard must be able to enforce this schema", report.enforceable()); //$NON-NLS-1$
        assertFalse("a top-level 'type' must not pass silently", report.isClean()); //$NON-NLS-1$
        assertEquals(1, report.unknownKeys().size());
        assertEquals("type", report.unknownKeys().get(0).key()); //$NON-NLS-1$
        assertEquals("the caller must be told where 'type' actually lives", //$NON-NLS-1$
                "properties.type", report.unknownKeys().get(0).suggestion()); //$NON-NLS-1$
    }

    @Test
    public void theSameCallWithTypeInsidePropertiesIsClean() {
        // The proof that the tool logic itself was never the problem: only the key placement was.
        SchemaKeyGuard.Report report = SchemaKeyGuard.inspect(ADD_CHILD_SCHEMA,
                List.of("project", "parent_fqn", "child_kind", "name", "properties"));
        assertTrue(report.isClean());
    }

    @Test
    public void nestedContentsAreNeverJudged() {
        // Only the keys handed to inspect() are checked; a free-form nested payload is not reachable
        // from here at all, which is the point — every metadata kind carries its own property names.
        SchemaKeyGuard.Report report = SchemaKeyGuard.inspect(ADD_CHILD_SCHEMA, List.of("properties"));
        assertTrue(report.isClean());
    }

    // --- fail-open (a false refusal is worse than the bug) -------------------

    @Test
    public void anUnparsableSchemaYieldsACleanUnenforceableReport() {
        SchemaKeyGuard.Report report = SchemaKeyGuard.inspect("{ not json", List.of("whatever")); //$NON-NLS-1$
        assertFalse(report.enforceable());
        assertTrue(report.isClean());
    }

    @Test
    public void aMissingOrEmptySchemaYieldsACleanUnenforceableReport() {
        assertTrue(SchemaKeyGuard.inspect(null, List.of("x")).isClean()); //$NON-NLS-1$
        assertTrue(SchemaKeyGuard.inspect("", List.of("x")).isClean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(SchemaKeyGuard.inspect("{}", List.of("x")).isClean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(SchemaKeyGuard.inspect("{\"properties\":{}}", List.of("x")).isClean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(SchemaKeyGuard.inspect("[1,2]", List.of("x")).isClean()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anExplicitAdditionalPropertiesTrueTurnsTheGuardOff() {
        // edt_diagnostics / qa_inspect / qa_generate say in so many words that they do not enumerate
        // their keys, because they route per-command parameters they never declare.
        String schema = """
                {
                  "type": "object",
                  "properties": {"command": {"type": "string"}},
                  "additionalProperties": true
                }
                """;
        SchemaKeyGuard.Report report = SchemaKeyGuard.inspect(schema, List.of("command", "project_name"));
        assertFalse(report.enforceable());
        assertTrue(report.isClean());
    }

    @Test
    public void anExplicitAdditionalPropertiesFalseKeepsTheGuardOn() {
        String schema = """
                {
                  "type": "object",
                  "properties": {"command": {"type": "string"}},
                  "additionalProperties": false
                }
                """;
        assertFalse(SchemaKeyGuard.inspect(schema, List.of("command", "nonsense")).isClean());
    }

    @Test
    public void extraAcceptedKeysAreNeverReported() {
        // A key some normalizer really does read is by definition not silently discarded.
        SchemaKeyGuard.Report report = SchemaKeyGuard.inspect(FLAT_SCHEMA,
                List.of("projectName", "moduleKind"), Set.of("moduleKind")); //$NON-NLS-1$
        assertTrue(report.isClean());
        assertTrue(report.acceptedKeys().contains("moduleKind")); //$NON-NLS-1$
    }

    @Test
    public void anEmptyArgumentMapIsCleanButStillEnforceable() {
        SchemaKeyGuard.Report report = SchemaKeyGuard.inspect(FLAT_SCHEMA, List.of());
        assertTrue(report.isClean());
        assertTrue(report.enforceable());
    }

    @Test
    public void nullKeysAreSkippedRatherThanReported() {
        SchemaKeyGuard.Report report = SchemaKeyGuard.inspect(FLAT_SCHEMA, java.util.Arrays.asList("scope", null));
        assertTrue(report.isClean());
    }

    // --- did-you-mean -------------------------------------------------------

    @Test
    public void aCaseOrUnderscoreVariantIsMatchedExactly() {
        SchemaKeyGuard.Report report = SchemaKeyGuard.inspect(ADD_CHILD_SCHEMA, List.of("parentFqn"));
        assertEquals("parent_fqn", report.unknownKeys().get(0).suggestion()); //$NON-NLS-1$
        assertEquals("project_name", SchemaKeyGuard.suggestFor("projectName", //$NON-NLS-1$ //$NON-NLS-2$
                List.of("project_name", "scope"), java.util.Map.of())); //$NON-NLS-1$
    }

    @Test
    public void aPluralOrSingularVariantIsMatched() {
        assertEquals("scope", SchemaKeyGuard.suggestFor("scopes", //$NON-NLS-1$ //$NON-NLS-2$
                List.of("scope", "limit"), java.util.Map.of())); //$NON-NLS-1$
        assertEquals("dimensions", SchemaKeyGuard.suggestFor("dimension", //$NON-NLS-1$ //$NON-NLS-2$
                List.of("dimensions", "resources"), java.util.Map.of())); //$NON-NLS-1$
    }

    @Test
    public void aShortEditDistanceTypoIsMatched() {
        SchemaKeyGuard.Report report = SchemaKeyGuard.inspect(FLAT_SCHEMA, List.of("projectNam"));
        assertEquals("projectName", report.unknownKeys().get(0).suggestion()); //$NON-NLS-1$
    }

    @Test
    public void anUnrelatedKeyGetsNoSuggestionRatherThanAWrongOne() {
        // scan_metadata_index's filter really is 'scope'; nothing in its schema resembles 'kinds', so
        // the message must fall back to listing the accepted keys instead of inventing a match.
        SchemaKeyGuard.Report report = SchemaKeyGuard.inspect(FLAT_SCHEMA, List.of("kinds"));
        assertEquals(1, report.unknownKeys().size());
        assertEquals("kinds", report.unknownKeys().get(0).key()); //$NON-NLS-1$
        assertNull(report.unknownKeys().get(0).suggestion());
    }

    @Test
    public void schemaKeywordsAreNotHarvestedAsNestedVocabulary() {
        // Every nested spec literally spells the word "type" in its own "type": "object" declaration.
        // Matching on that would make ANY unknown key look like it belonged in the first nested
        // container, so only property NAMES and description prose may be harvested.
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "name": {"type": "string", "description": "Name"},
                    "bucket": {"type": "object", "description": "Free-form bag"}
                  }
                }
                """;
        SchemaKeyGuard.Report report = SchemaKeyGuard.inspect(schema, List.of("required"));
        assertNull(report.unknownKeys().get(0).suggestion());
    }

    @Test
    public void aKeyDocumentedInsideAnArrayItemIsPointedAtTheArray() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "project": {"type": "string", "description": "EDT project"},
                    "operations": {
                      "type": "array",
                      "items": {"type": "object", "properties": {"op": {"type": "string"}}},
                      "description": "List of operations"
                    }
                  }
                }
                """;
        SchemaKeyGuard.Report report = SchemaKeyGuard.inspect(schema, List.of("op"));
        assertEquals("operations.op", report.unknownKeys().get(0).suggestion()); //$NON-NLS-1$
    }

    @Test
    public void everyUnknownKeyIsReportedNotJustTheFirst() {
        SchemaKeyGuard.Report report = SchemaKeyGuard.inspect(FLAT_SCHEMA, List.of("scope", "kinds", "wat"));
        assertEquals(2, report.unknownKeys().size());
    }

    // --- messages -----------------------------------------------------------

    @Test
    public void theRefusalNamesTheKeyTheIntentAndTheAcceptedKeys() {
        SchemaKeyGuard.Report report = SchemaKeyGuard.inspect(ADD_CHILD_SCHEMA,
                List.of("project", "parent_fqn", "child_kind", "name", "type"));
        String message = SchemaKeyGuard.refusalMessage("add_metadata_child", report.unknownKeys(), //$NON-NLS-1$
                SchemaKeyGuard.forDisplay(report.acceptedKeys(), Set.of("validation_token"))); //$NON-NLS-1$

        assertTrue(message, message.contains("'type'")); //$NON-NLS-1$
        assertTrue(message, message.contains("did you mean 'properties.type'?")); //$NON-NLS-1$
        assertTrue(message, message.contains("No validation token was issued")); //$NON-NLS-1$
        assertTrue(message, message.contains("Accepted top-level payload keys: child_kind, name," //$NON-NLS-1$
                + " parent_fqn, project, properties, template_type.")); //$NON-NLS-1$
        assertFalse("the payload never carries validation_token, so it must not be advertised", //$NON-NLS-1$
                message.contains("validation_token")); //$NON-NLS-1$
    }

    @Test
    public void theRefusalUsesPluralWordingForSeveralKeys() {
        SchemaKeyGuard.Report report = SchemaKeyGuard.inspect(FLAT_SCHEMA, List.of("kinds", "wat"));
        String message = SchemaKeyGuard.refusalMessage("scan", report.unknownKeys(), //$NON-NLS-1$
                SchemaKeyGuard.forDisplay(report.acceptedKeys(), null));
        assertTrue(message, message.contains("2 unknown top-level keys")); //$NON-NLS-1$
    }

    @Test
    public void theAdvisoryIsAppendableAndNamesTheIgnoredKey() {
        SchemaKeyGuard.Report report = SchemaKeyGuard.inspect(FLAT_SCHEMA, List.of("projectName", "kinds"));
        String advisory = SchemaKeyGuard.advisoryLine("scan_metadata_index", report.unknownKeys(), //$NON-NLS-1$
                SchemaKeyGuard.forDisplay(report.acceptedKeys(), null));

        assertTrue(advisory.startsWith("\n\nNote: scan_metadata_index ignored an unknown parameter:")); //$NON-NLS-1$
        assertTrue(advisory, advisory.contains("'kinds'")); //$NON-NLS-1$
        assertTrue(advisory, advisory.contains("had no effect")); //$NON-NLS-1$
        assertTrue(advisory, advisory.contains("Accepted parameters: limit, projectName, scope.")); //$NON-NLS-1$
    }

    // --- helpers ------------------------------------------------------------

    @Test
    public void normalizeCollapsesSeparatorsAndCase() {
        assertEquals("parentfqn", SchemaKeyGuard.normalize("Parent_FQN")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("parentfqn", SchemaKeyGuard.normalize("parentFqn")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("", SchemaKeyGuard.normalize(null)); //$NON-NLS-1$
    }

    @Test
    public void editDistanceIsBoundedAndAbandonsEarly() {
        assertEquals(0, SchemaKeyGuard.editDistance("abc", "abc", 2)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, SchemaKeyGuard.editDistance("abc", "abd", 2)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(SchemaKeyGuard.editDistance("abc", "zzzzzz", 2) > 2); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void forDisplaySortsAndHides() {
        assertEquals(List.of("a", "b"), //$NON-NLS-1$ //$NON-NLS-2$
                SchemaKeyGuard.forDisplay(List.of("b", "secret", "a"), Set.of("secret"))); //$NON-NLS-1$
    }
}
