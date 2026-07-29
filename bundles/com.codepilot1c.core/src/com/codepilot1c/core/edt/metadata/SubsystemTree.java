package com.codepilot1c.core.edt.metadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Pure depth-first traversal of a subsystem forest (BF-12936 / B4).
 *
 * <p>Subsystems are the only top-level kind that nests: {@code Configuration.subsystems}
 * and {@code Subsystem.subsystems} are both <em>non-containment</em> references, so a
 * nested subsystem has {@code eContainer() == null} and gets its own {@code .mdo}. Every
 * nested subsystem is a top object in its own right.</p>
 *
 * <p>Consequence: a lookup that only scans {@code Configuration.getSubsystems()} sees the
 * first level and reports {@code exists:false} for everything below it, even though the
 * flat form {@code Subsystem.PaymentCalendar} is the address callers naturally write. This
 * class supplies the recursion, and — because flat names cannot distinguish two subsystems
 * that share a name under different parents — it reports <em>all</em> hits so the caller
 * can fail loud instead of silently picking one.</p>
 *
 * <p><strong>The flat form is an alias, not the storage FQN.</strong> Earlier rounds of this
 * class claimed a subsystem's canonical FQN is flat at any depth. It is not. Decompiled from
 * {@code MdTopObjectFqnGeneratorDelegate.generateNamedExternalPropertyFqnInternal} (EDT
 * 2025.2.3): a subsystem owned by {@code Configuration.subsystems} is registered as
 * {@code Subsystem.<Name>}, while one owned by {@code Subsystem.subsystems} is registered as
 * {@code <ownerFqn>.Subsystem.<Name>} — a dotted chain that grows with the nesting. The chain is
 * what {@code QualifiedNameFilePathConverter} turns into
 * {@code src/Subsystems/<A>/Subsystems/<B>/<B>.mdo} (its {@code handleCommonResource} recurses on
 * exactly the {@code Subsystem} marker), and it is what the live {@code bmGetFqn()} of a nested
 * subsystem in an EDT-authored configuration reads back as. {@link #qualifiedName} is that rule;
 * the tree walk above is what keeps the flat alias working on top of it.</p>
 *
 * <p>Kept free of EMF/BM types on purpose: the traversal and the FQN shape are the parts worth
 * unit-testing, and EMF model classes do not resolve in the plain Maven test bundle.</p>
 */
public final class SubsystemTree {

    /** Parent label used for a subsystem that sits directly on the configuration. */
    public static final String CONFIGURATION_ROOT = "Configuration (top level)"; //$NON-NLS-1$

    /** The marker segment that introduces a subsystem inside a qualified name. */
    public static final String SUBSYSTEM_SEGMENT = "Subsystem"; //$NON-NLS-1$

    private SubsystemTree() {
    }

    /**
     * The BM top-object FQN a subsystem named {@code name} must carry while it is owned by
     * {@code ownerFqn}.
     *
     * <p>{@code ownerFqn} is the FQN of the owning <em>subsystem</em>; a blank or {@code null}
     * owner means the configuration root, which is the only owner that does not contribute a
     * segment. Mirrors EDT's own generator (see the class javadoc), so a re-parent can move the
     * storage to the slot the new owner's {@code .mdo} reference will resolve against.</p>
     *
     * @return the FQN, or {@code null} when {@code name} is absent — an unnamed subsystem has no
     *         addressable slot and must be left where it is rather than moved somewhere unnameable
     */
    public static String qualifiedName(String ownerFqn, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String leaf = SUBSYSTEM_SEGMENT + "." + name.trim(); //$NON-NLS-1$
        if (ownerFqn == null || ownerFqn.isBlank()) {
            return leaf;
        }
        return ownerFqn.trim() + "." + leaf; //$NON-NLS-1$
    }

    /**
     * The subsystem names encoded in a subsystem FQN, outermost first: {@code Subsystem.A} yields
     * {@code [A]} and {@code Subsystem.A.Subsystem.B} yields {@code [A, B]}.
     *
     * <p>Empty for anything that is not a well-formed subsystem chain — an odd segment count, a
     * marker that is not {@code Subsystem}, a blank name. Strict on purpose: the callers use the
     * chain to name files, and a half-understood FQN must yield no path rather than a wrong one.</p>
     */
    public static List<String> nameChain(String fqn) {
        if (fqn == null || fqn.isBlank()) {
            return List.of();
        }
        String[] parts = fqn.trim().split("\\."); //$NON-NLS-1$
        if (parts.length < 2 || parts.length % 2 != 0) {
            return List.of();
        }
        List<String> names = new ArrayList<>(parts.length / 2);
        for (int i = 0; i < parts.length; i += 2) {
            if (!SUBSYSTEM_SEGMENT.equalsIgnoreCase(parts[i]) || parts[i + 1].isBlank()) {
                return List.of();
            }
            names.add(parts[i + 1]);
        }
        return List.copyOf(names);
    }

    /**
     * One subsystem's re-registration, forced on it by the move of an ancestor.
     *
     * @param node the subsystem to re-register
     * @param parent the subsystem that owns {@code node} — for the top level of the plan that is the
     *        moved subsystem itself, deeper down it is the planned node above. Carried because
     *        re-registering the FQN is only half the move: the node's own {@code parentSubsystem}
     *        up-link holds a proxy resolved against the owner's OLD chain, and handing the caller the
     *        live owner object is what lets it replace that stale proxy — see
     *        {@code EdtMetadataService.relocateSubsystemDescendants}
     * @param previousFqn the FQN it is registered under now, or {@code null} when BM cannot answer
     * @param targetFqn the FQN its owner's new position dictates, or {@code null} when the node has
     *        no readable name — there is then no slot to name for it, and the caller must leave it
     *        alone rather than move it somewhere unnameable
     */
    public record Relocation<T>(T node, T parent, String previousFqn, String targetFqn) {
    }

    /**
     * The re-registrations a subsystem's move forces on the subsystems BELOW it, parents before
     * children.
     *
     * <p>{@code updateTopObjectFqn} moves exactly the one object it is given, and a subsystem's
     * children are separate top objects with FQN chains of their own — so a move that touches only
     * the moved subsystem leaves every descendant registered under a chain whose root no longer
     * exists. Live-measured 2026-07-29: after {@code WaveR8P} (holding {@code WaveR8C}) moved under
     * {@code WaveParent}, the parent's own down-link read back as a NAMELESS stub and
     * {@code Subsystem.WaveR8C} answered "Object not found" to every tool, with no way to repair it
     * through one either.</p>
     *
     * <p>Every FQN here is read BEFORE any of them is written, which is the property that makes the
     * plan usable: the {@code subsystems} down-links are bare names resolved against the owner's
     * FQN, so they stop resolving the moment the owner moves. Descending into a node whose own
     * target FQN is unknown would have to guess its children's chain, so that subtree is reported
     * as one unnameable entry and left untouched instead.</p>
     *
     * @param ownerFqn the FQN the owner will carry AFTER its move; blank or {@code null} means the
     *        configuration root
     * @param owner the subsystem being moved; its children are read through {@code childrenOf}, and
     *        it is itself the {@link Relocation#parent()} of the plan's top level — the owner object
     *        has to reach the caller, because each descendant's up-link has to be re-pointed at the
     *        live parent and not merely re-keyed
     * @param currentFqnOf reads a node's live registered FQN
     */
    public static <T> List<Relocation<T>> descendantRelocations(
            String ownerFqn,
            T owner,
            Function<? super T, String> nameOf,
            Function<? super T, String> currentFqnOf,
            Function<? super T, ? extends List<? extends T>> childrenOf
    ) {
        List<Relocation<T>> plan = new ArrayList<>();
        if (owner == null) {
            return plan;
        }
        planRelocations(ownerFqn, owner, childrenOf.apply(owner), nameOf, currentFqnOf, childrenOf,
                newIdentitySet(), plan);
        return plan;
    }

    /**
     * A node found by name together with the names of its ancestors, root-first.
     * An empty {@code parentPath} means the node sits directly on the configuration.
     */
    public record Located<T>(T node, List<String> parentPath) {

        public Located {
            parentPath = parentPath == null ? List.of() : List.copyOf(parentPath);
        }

        /** Human-readable parent chain, for ambiguity reports. */
        public String describePath() {
            return parentPath.isEmpty() ? CONFIGURATION_ROOT : String.join(" > ", parentPath); //$NON-NLS-1$
        }
    }

    /**
     * Flattens the forest depth-first, parents before children. Duplicate node instances
     * are visited once (identity-based), so a malformed model with a cyclic
     * {@code subsystems} link cannot hang the traversal.
     */
    public static <T> List<T> flatten(
            List<? extends T> roots,
            Function<? super T, ? extends List<? extends T>> childrenOf
    ) {
        List<T> flat = new ArrayList<>();
        collect(roots, childrenOf, newIdentitySet(), flat);
        return flat;
    }

    /**
     * Collects every node whose name matches {@code name} (case-insensitively), at any
     * depth. Returns an empty list when nothing matches; more than one element means the
     * flat FQN is ambiguous and the caller must refuse rather than choose.
     */
    public static <T> List<Located<T>> locateByName(
            List<? extends T> roots,
            String name,
            Function<? super T, String> nameOf,
            Function<? super T, ? extends List<? extends T>> childrenOf
    ) {
        List<Located<T>> hits = new ArrayList<>();
        if (name == null || name.isBlank()) {
            return hits;
        }
        walk(roots, name.trim(), nameOf, childrenOf, new ArrayList<>(), newIdentitySet(), hits);
        return hits;
    }

    /**
     * Builds the refusal text for an ambiguous flat FQN, naming every parent so the caller
     * can see which objects collide instead of guessing.
     */
    public static String describeAmbiguity(String fqnPrefix, String name, List<? extends Located<?>> hits) {
        StringBuilder message = new StringBuilder();
        message.append("Ambiguous ").append(fqnPrefix).append(" name '").append(name).append("': ") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .append(hits.size())
                .append(" objects share it (parents: "); //$NON-NLS-1$
        for (int i = 0; i < hits.size(); i++) {
            if (i > 0) {
                message.append("; "); //$NON-NLS-1$
            }
            message.append(hits.get(i).describePath());
        }
        message.append("). ").append(fqnPrefix) //$NON-NLS-1$
                .append(" FQNs are flat (").append(fqnPrefix).append(".<Name>), so this name cannot address") //$NON-NLS-1$ //$NON-NLS-2$
                .append(" a single object. Rename one of them, or address the one you mean by its nested") //$NON-NLS-1$
                .append(" alias ").append(fqnPrefix).append(".<Parent>.").append(fqnPrefix).append(".<Name>."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return message.toString();
    }

    /**
     * Builds the refusal text for an FQN whose nested part does not parse as marker/name pairs
     * (F2).
     *
     * <p>The general rule — every nested segment is a {@code <Marker>.<Name>} pair — is right for
     * every kind that owns containment children, and a caller who passed
     * {@code Catalog.Foo.Attribute} is told exactly what is missing. For a SUBSYSTEM head it sent
     * the caller down a dead end: {@code Subsystem.Parent.Child} was rejected as an unpaired
     * segment, which reads as "add the marker" — and the message then denied the very form that
     * advice produces. {@code Subsystem.Parent.Subsystem.Child} IS the FQN a nested subsystem is
     * registered under, and the resolver accepts it (live-confirmed 2026-07-29: {@code
     * update_metadata} took it and answered with it). So does the flat alias. Only the unpaired form
     * the caller wrote resolves nowhere, so the message now names BOTH ways out instead of declaring
     * one of them nonexistent — which also stopped it contradicting {@link #describeAmbiguity}, whose
     * escape from an ambiguous flat name is exactly that chain.</p>
     *
     * @param headIsSubsystem whether the FQN's leading type token addresses a subsystem
     */
    public static String nestedFqnRejectionMessage(String fqn, boolean headIsSubsystem) {
        if (!headIsSubsystem) {
            return "Nested FQN segments must be marker/name pairs: " + fqn; //$NON-NLS-1$
        }
        return "A subsystem is addressed by either of two forms, and this is neither: the flat" //$NON-NLS-1$
                + " Subsystem.<Name>, which reaches a subsystem at any nesting depth by name, or the" //$NON-NLS-1$
                + " paired chain Subsystem.<Parent>.Subsystem.<Name>, which is the FQN a nested" //$NON-NLS-1$
                + " subsystem is registered under. Put the missing Subsystem marker before each" //$NON-NLS-1$
                + " nested name, or drop the parent segments and pass the flat form: " + fqn; //$NON-NLS-1$
    }

    private static <T> void planRelocations(
            String ownerFqn,
            T owner,
            List<? extends T> nodes,
            Function<? super T, String> nameOf,
            Function<? super T, String> currentFqnOf,
            Function<? super T, ? extends List<? extends T>> childrenOf,
            Set<Object> visited,
            List<Relocation<T>> plan
    ) {
        if (nodes == null) {
            return;
        }
        for (T node : nodes) {
            if (node == null || !visited.add(node)) {
                continue;
            }
            String targetFqn = qualifiedName(ownerFqn, nameOf.apply(node));
            plan.add(new Relocation<>(node, owner, currentFqnOf.apply(node), targetFqn));
            if (targetFqn == null) {
                continue;
            }
            planRelocations(targetFqn, node, childrenOf.apply(node), nameOf, currentFqnOf, childrenOf,
                    visited, plan);
        }
    }

    private static <T> void collect(
            List<? extends T> nodes,
            Function<? super T, ? extends List<? extends T>> childrenOf,
            Set<Object> visited,
            List<T> sink
    ) {
        if (nodes == null) {
            return;
        }
        for (T node : nodes) {
            if (node == null || !visited.add(node)) {
                continue;
            }
            sink.add(node);
            collect(childrenOf.apply(node), childrenOf, visited, sink);
        }
    }

    private static <T> void walk(
            List<? extends T> nodes,
            String name,
            Function<? super T, String> nameOf,
            Function<? super T, ? extends List<? extends T>> childrenOf,
            List<String> path,
            Set<Object> visited,
            List<Located<T>> hits
    ) {
        if (nodes == null) {
            return;
        }
        for (T node : nodes) {
            if (node == null || !visited.add(node)) {
                continue;
            }
            String nodeName = nameOf.apply(node);
            if (nodeName != null && nodeName.equalsIgnoreCase(name)) {
                hits.add(new Located<>(node, path));
            }
            path.add(nodeName == null ? "" : nodeName); //$NON-NLS-1$
            walk(childrenOf.apply(node), name, nameOf, childrenOf, path, visited, hits);
            path.remove(path.size() - 1);
        }
    }

    private static Set<Object> newIdentitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }
}
