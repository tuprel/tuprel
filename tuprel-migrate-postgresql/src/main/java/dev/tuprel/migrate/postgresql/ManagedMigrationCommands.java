package dev.tuprel.migrate.postgresql;

import dev.tuprel.sql.SqlIdentifier;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Private command adapter for the operator's protected installation and single target. */
final class ManagedMigrationCommands {
    private static final Set<String> FLAGS = Set.of("--write", "--dry-run");
    private final Path project;
    private final SqlIdentifier target;
    private final Path installation;
    private final PublicKey publisherKey;
    private final InitialBootstrapAuthorization proof;
    private final Supplier<ManagedCredentialIssuer> issuers;
    private final Clock clock;

    ManagedMigrationCommands(Path project, SqlIdentifier target, Path installation, PublicKey publisherKey,
            InitialBootstrapAuthorization proof, Supplier<ManagedCredentialIssuer> issuers, Clock clock) {
        this.project = project;
        this.target = target;
        this.installation = installation;
        this.publisherKey = publisherKey;
        this.proof = proof;
        this.issuers = issuers;
        this.clock = clock;
    }

    int run(List<String> arguments, PrintWriter out, PrintWriter err) {
        if (arguments.size() < 2 || !arguments.getFirst().equals("migrate")) { return usage(err); }
        Map<String, String> options = new HashMap<>();
        for (int index = 2; index < arguments.size(); index++) {
            String option = arguments.get(index);
            if (!option.startsWith("--") || options.containsKey(option)) { return usage(err); }
            if (FLAGS.contains(option)) { options.put(option, ""); }
            else if (index + 1 >= arguments.size() || arguments.get(index + 1).startsWith("-")) { return usage(err); }
            else { options.put(option, arguments.get(++index)); }
        }
        if (!target.name().equals(options.remove("--target-schema"))) { return usage(err); }
        Path catalog = project.resolve("tuprel/migrations");
        return switch (arguments.get(1)) {
            case "dev" -> publish(catalog, options, out, err);
            case "deploy" -> deploy(catalog, options, out, err);
            default -> usage(err);
        };
    }

    private int publish(Path catalog, Map<String, String> options, PrintWriter out, PrintWriter err) {
        if (!options.containsKey("--write") || !options.containsKey("--name")
                || !Set.of("--write", "--name", "--schema").containsAll(options.keySet())) { return usage(err); }
        Path source;
        try { source = Path.of(options.getOrDefault("--schema", "tuprel/schema.tuprel")); }
        catch (IllegalArgumentException failure) { return usage(err); }
        ManagedDevPublication.Result result = issuers.get().publish(catalog, source, options.get("--name"),
                installation, publisherKey, proof, clock);
        out.println("Publication: " + result.state());
        result.proposal().flatMap(MigrationDevPlan::sql).ifPresent(out::print);
        result.file().ifPresent(file -> out.println("Published " + file.directoryName() + " SHA-256 " + file.checksum().value()));
        return switch (result.state()) {
            case NO_CHANGES -> 0;
            case PUBLISHED -> {
                out.println("Reconcile and acknowledge the publication receipt, then request a new physical deploy preview.");
                yield 0;
            }
            case INVALID_SCHEMA -> 1;
            case PUBLICATION_UNKNOWN, COLLISION -> {
                err.println("Preserve the receipt and inspect the recorded publication before another attempt.");
                yield 2;
            }
            case REFUSED -> {
                err.println("TUPREL-MANAGED-PUBLICATION: " + result.diagnostic().orElseThrow());
                yield 2;
            }
        };
    }

    private int deploy(Path catalog, Map<String, String> options, PrintWriter out, PrintWriter err) {
        if (options.keySet().equals(Set.of("--dry-run"))) {
            try {
                ManagedCredentialIssuer.DeploymentReview review = issuers.get().review(catalog, proof);
                out.println("Plan: " + review.plan().state());
                out.println("Applied: " + review.applied());
                out.println("Risks: " + review.plan().risks().stream().sorted().toList());
                for (MigrationPlan.Script script : review.plan().scripts()) {
                    out.println("Pending " + script.id().value() + "_" + script.name() + " SHA-256 " + script.checksum().value());
                }
                out.print(review.plan().preview());
                review.token().ifPresent(token -> out.println("Physical approval: " + token));
                return 0;
            } catch (MigrationInspectionException failure) {
                err.println("TUPREL-MANAGED-REVIEW: " + failure.reason());
                return 2;
            }
        }
        if (!options.keySet().equals(Set.of("--approve-physical"))) { return usage(err); }
        ManagedCredentialIssuer issuer = issuers.get();
        if (!issuer.verifiedPublicationProfile(catalog, installation, publisherKey, proof, clock)) {
            err.println("TUPREL-MANAGED-DEPLOY: INSTALLATION");
            return 2;
        }
        MigrationExecutionResult result = issuer.deployReviewed(catalog, options.get("--approve-physical"), proof);
        out.println("Deployment: " + result.outcome());
        out.println("Confirmed this invocation: " + result.confirmed().size());
        if (result.outcome() == MigrationExecutionResult.Outcome.APPLIED
                || result.outcome() == MigrationExecutionResult.Outcome.NO_CHANGES) { return 0; }
        err.println("TUPREL-MANAGED-DEPLOY: " + result.failure());
        if (result.outcome() == MigrationExecutionResult.Outcome.OUTCOME_UNKNOWN) {
            err.println("Inspect the confirmed prefix and reconcile current history before another reviewed invocation.");
        }
        return 2;
    }

    private static int usage(PrintWriter err) {
        err.println("Usage: migrate dev --target-schema <name> --name <name> --write [--schema <path>]");
        err.println("       migrate deploy --target-schema <name> (--dry-run | --approve-physical <token>)");
        return 2;
    }
}
