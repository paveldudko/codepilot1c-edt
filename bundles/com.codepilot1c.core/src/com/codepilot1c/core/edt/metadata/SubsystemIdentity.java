/**
 * Copyright (c) 2025 codepilot1c contributors.
 */
package com.codepilot1c.core.edt.metadata;

/**
 * Identity of a subsystem for membership tests inside a parent's {@code subsystems} collection.
 *
 * <p>The name alone is not enough. Once the {@code .mdo} files are loaded, a parent's
 * {@code subsystems} collection holds <b>unresolved EMF proxies</b>: {@code eIsProxy() == true},
 * {@code getName() == null}, {@code bmIsTransient() == true}. Only the proxy URI carries the
 * identity, and it carries it as a dotted containment chain. Observed live 2026-07-29 on the
 * sandbox, one diagnostic line per entry of {@code WaveParent.subsystems}:</p>
 *
 * <pre>
 * parent=WaveParent child=WaveChild childProxy=false childBmFqn=Subsystem.WaveChild
 * existing=[class=Subsystem impl=SubsystemImpl proxy=true name=null bmFqn=transient
 *           uri=bm://TestConfiguration/Subsystem.WaveParent.Subsystem.WaveChild#/ | ...]
 * </pre>
 *
 * <p>So a name-only comparison never matched an existing entry, and every repeated
 * {@code set.parentSubsystem} appended another {@code <subsystems>} line.</p>
 *
 * <p>Deliberately free of any EMF/EDT type so the rule is exercised by result rather than pinned by
 * reading the source: the callers read {@code getName()} and the URI off the model and pass strings
 * in. The URI reading is strict on purpose — the last slash segment must be a dotted chain whose
 * penultimate element is literally {@code Subsystem}, and only then is the final element the name.
 * Anything else yields no identity. That asymmetry is the point: a missed match merely reproduces
 * the old duplicate, while a wrong match would silently drop somebody's real child.</p>
 */
final class SubsystemIdentity {

    /** The metadata-kind element that must precede the name in a subsystem URI chain. */
    private static final String SUBSYSTEM_SEGMENT = "Subsystem"; //$NON-NLS-1$

    private SubsystemIdentity() {
        // utility
    }

    /**
     * The comparable identity of a subsystem given whatever the model could answer: its name when
     * readable, else the name encoded in its URI. {@code null} when neither yields one.
     */
    static String of(String name, String uri) {
        if (name != null && !name.isBlank()) {
            return name.toLowerCase();
        }
        return nameFromUri(uri);
    }

    /**
     * The subsystem name encoded in a BM/EMF URI, or {@code null} when the URI does not have the
     * shape {@code .../<chain>.Subsystem.<Name>[#fragment]}.
     */
    static String nameFromUri(String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        String path = uri;
        int hash = path.indexOf('#');
        if (hash >= 0) {
            path = path.substring(0, hash);
        }
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash >= 0) {
            path = path.substring(lastSlash + 1);
        }
        int lastDot = path.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == path.length() - 1) {
            return null;
        }
        String owner = path.substring(0, lastDot);
        int ownerLastDot = owner.lastIndexOf('.');
        String kind = ownerLastDot >= 0 ? owner.substring(ownerLastDot + 1) : owner;
        if (!SUBSYSTEM_SEGMENT.equalsIgnoreCase(kind)) {
            return null;
        }
        return path.substring(lastDot + 1).toLowerCase();
    }

    /**
     * Whether two identities denote the same subsystem. An absent identity matches nothing, not even
     * another absent one — an entry we cannot name must never be treated as a duplicate.
     */
    static boolean same(String leftIdentity, String rightIdentity) {
        return leftIdentity != null && leftIdentity.equals(rightIdentity);
    }
}
