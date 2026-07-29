/**
 * Copyright (c) 2025 codepilot1c contributors.
 */
package com.codepilot1c.core.edt.metadata;

import java.util.List;

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

    /**
     * The metadata-kind element that must precede the name in a subsystem URI chain — the same marker
     * {@link SubsystemTree#qualifiedName} builds the FQN chain out of, which is no coincidence: the
     * URI observed here IS that chain.
     */
    private static final String SUBSYSTEM_SEGMENT = SubsystemTree.SUBSYSTEM_SEGMENT;

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

    /**
     * The full owner CHAIN of a subsystem, lowercased — {@code subsystem.waveparent.subsystem.child}
     * — or {@code null} when neither the FQN nor the URI spells a well-formed chain.
     *
     * <p><strong>Not interchangeable with {@link #of}.</strong> {@code of} answers "which subsystem is
     * this", by leaf name, and that is right for a membership test inside one parent's collection.
     * It is WRONG for the question "does this pointer still point where it should", because a stale
     * proxy and the live object it went stale on share a leaf name. Live-measured 2026-07-29: after
     * {@code WaveR9P} moved under {@code WaveParent}, its child's {@code parentSubsystem} held a proxy
     * at {@code Subsystem.WaveR9P} while the live parent was
     * {@code Subsystem.WaveParent.Subsystem.WaveR9P} — both named {@code waver9p}, so the name-based
     * comparison answered "same", the pointer write was skipped, and {@code update_metadata} reported
     * SUCCESS having changed nothing. The chain is what tells those two apart.</p>
     *
     * <p>The live FQN wins when BM can answer it; the URI is the fallback, because a dangling proxy is
     * exactly the case where BM cannot and the URI is the only thing still carrying the old chain.</p>
     */
    static String chainOf(String fqn, String uri) {
        String fromFqn = chainFrom(fqn);
        return fromFqn != null ? fromFqn : chainFrom(uriPath(uri));
    }

    /**
     * Whether two chains denote the same position in the subsystem tree. An unreadable chain matches
     * nothing, so a caller deciding whether to write a pointer writes it — the repairing direction.
     * Guessing "already correct" from an unreadable chain is what leaves a dangling proxy in place.
     */
    static boolean sameChain(String leftChain, String rightChain) {
        return leftChain != null && leftChain.equals(rightChain);
    }

    /** The canonical lowercased chain of {@code candidate}, or {@code null} when it is not one. */
    private static String chainFrom(String candidate) {
        List<String> names = SubsystemTree.nameChain(candidate);
        if (names.isEmpty()) {
            return null;
        }
        StringBuilder chain = new StringBuilder();
        for (String name : names) {
            if (chain.length() > 0) {
                chain.append('.');
            }
            chain.append(SUBSYSTEM_SEGMENT).append('.').append(name);
        }
        return chain.toString().toLowerCase();
    }

    /** The dotted chain a BM/EMF URI carries: its last slash segment, fragment stripped. */
    private static String uriPath(String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        String path = uri;
        int hash = path.indexOf('#');
        if (hash >= 0) {
            path = path.substring(0, hash);
        }
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }
}
