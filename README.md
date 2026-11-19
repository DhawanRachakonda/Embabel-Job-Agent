# Job Agent: Embabel (Java)

Java port of the Python [Langgraph-Job-Agent](../Langgraph-Job-Agent) on the [Embabel](https://docs.embabel.com) agent framework (1.5.4, Spring Boot 4). A recruiter pastes a messy job description; the agent extracts the fields, asks for anything missing, rejects duplicates, scores and rewrites the text if needed, and publishes it to MongoDB. A **React** chat UI sits on top.

## How the flow is expressed

There is no graph definition. The flow comes from the method signatures in `JdIntakeAgent`:

```
parse(RawJd)            -> Stage      Unchecked | Collecting
ask(Collecting)         -> FieldAnswer   (WaitFor.formSubmission: pauses for the recruiter)
absorb(Collecting, FieldAnswer) -> Stage   Collecting (loop) | Unchecked | Escalated
checkDuplicate(Unchecked) -> Stage    Duplicate | Scoring
score(Scoring)          -> Stage      Publishable | NeedsRewrite | Escalated
rewrite(NeedsRewrite)   -> Scoring    (loop back to scoring)
publish / reject / escalate -> JobOutcome   (the three goals)
```

Which fields count as missing is declared with Jakarta Bean Validation: the `RequiredFields` record in `JdDomain` carries `@NotBlank` constraints, and `RequiredFields.missing(validator)` (called from `parse`) validates it with the Spring `Validator` bean. The extraction prompt does not list the fields: `createObject(prompt, ExtractedFields.class)` lets the output type say what to extract, and `ExtractedFields.toRequiredFields()` hands the values to the constrained record (the guide says Embabel supports Jakarta Bean Validation for domain constraints). The constraints are kept off `ExtractedFields`, the type the model fills, because a field the job description simply doesn't mention is a gap to ask about, not a malformed reply.

The `Stage` records in `JdDomain` are the states. Returning a different stage type is a branch; the same type is a loop. Rules (75 threshold, 2 rewrites, 6 questions) are plain Java inside the actions, not LLM decisions. State-transition actions use `clearBlackboard = true` so old states and consumed answers can't be picked up again.

## Pause and resume across a restart

With `embabel.agent.platform.persistence` enabled (see `application.yml`), a run waiting for the recruiter is snapshotted to a database through Embabel's `JdbcAgentProcessSnapshotStore` (file-backed H2 in PostgreSQL mode by default; use Postgres in production).

Embabel 1.5.4 cannot restore its own `FormBindingRequest` from a snapshot (it fails with a Jackson error). `FormRequestSerializer` is a small workaround: it stores the question and answer type and rebuilds the form. Check whether a newer Embabel version makes it unnecessary.

Also per Embabel's docs, restoring supports only the in-memory blackboard and assumes one pending awaitable per waiting process, which matches this pipeline.

## Requirements

Java 21+, Maven, Node 18+ (for the UI), an Anthropic API key, and a MongoDB Atlas cluster (add your IP under Network Access, otherwise you get SSL handshake errors).

## Setup and run

```bash
cp .env.example .env      # fill in ANTHROPIC_API_KEY and MONGO_URI (or export them as environment variables)
cd frontend && npm install && npm run build && cd ..
mvn spring-boot:run
```

Open http://127.0.0.1:8080. For frontend hot reload: `cd frontend && npm run dev` (backend must be running).

## Tests

```bash
mvn test
```

These run the real Embabel platform and planner with the LLM mocked (Embabel's `EmbabelMockitoIntegrationTest`): publish, duplicate rejection, one-at-a-time questions through `WaitFor`, the rewrite cap, escalation, the `/chat` contract, and `PersistenceRestartTest`, where a fresh application context over the same database file resumes a paused run.

## Notes

- Jobs are stored in MongoDB database `jd_intake_db`, collection `jobs_embabel` (override with `MONGO_DB` and `MONGO_COLLECTION`). Each sibling app uses its own collection, and the Python original uses `jobs`, so several apps can share one cluster without the duplicate check (same title and company) seeing each other's jobs.
- Users are told apart only by `thread_id` (the Embabel process id), which the React app keeps in memory and sends back with each answer. There is no login.
- Embabel's `MONGO_URI` bean is created only when `MONGO_URI` is set.
