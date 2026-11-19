package jobagent;

import com.embabel.agent.core.Agent;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.AgentProcessStatusCode;
import com.embabel.agent.core.ProcessOptions;
import com.embabel.agent.core.hitl.FormBindingRequest;
import com.embabel.agent.core.hitl.FormResponse;
import com.embabel.ux.form.FormSubmission;
import jobagent.JdDomain.JobOutcome;
import jobagent.JdDomain.RawJd;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Runs the JD intake agent and bridges its human-in-the-loop pauses to plain strings. */
@Service
public class PipelineService {

    /** Either a question for the recruiter ({@code outcome == null}) or the final outcome. */
    public record Reply(String processId, String question, JobOutcome outcome) {
        public boolean paused() { return question != null; }
    }

    private final AgentPlatform platform;

    public PipelineService(AgentPlatform platform) {
        this.platform = platform;
    }

    /** Starts a new run for a raw job description. */
    public Reply start(String rawJd) {
        Agent agent = platform.agents().stream()
                .filter(a -> a.getName().contains("JdIntakeAgent"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("JdIntakeAgent is not deployed"));
        AgentProcess process = platform.createAgentProcessFrom(agent, new ProcessOptions(), new RawJd(rawJd));
        return toReply(process.run());
    }

    /** Resumes a paused run with the recruiter's answer to the pending question. */
    public Reply answer(String processId, String text) {
        AgentProcess process = platform.getAgentProcess(processId);
        if (process == null) {
            throw new IllegalArgumentException("No such process: " + processId);
        }
        if (!(process.lastResult() instanceof FormBindingRequest<?> request)) {
            throw new IllegalStateException("Process " + processId + " is not waiting for an answer");
        }
        // The form generated from FieldAnswer has a single control named "value".
        FormSubmission submission = new FormSubmission(
                request.getPayload().getId(), Map.of("value", text), UUID.randomUUID().toString(), Instant.now());
        FormResponse response = new FormResponse(
                UUID.randomUUID().toString(), request.getId(), submission, false, Instant.now());
        request.onResponse(response, process);
        return toReply(process.run());
    }

    private Reply toReply(AgentProcess process) {
        if (process.getStatus() == AgentProcessStatusCode.WAITING
                && process.lastResult() instanceof FormBindingRequest<?> request) {
            return new Reply(process.getId(), request.getPayload().getTitle(), null);
        }
        if (process.getStatus() == AgentProcessStatusCode.COMPLETED) {
            return new Reply(process.getId(), null, process.resultOfType(JobOutcome.class));
        }
        throw new IllegalStateException("Run ended in status " + process.getStatus()
                + " (" + process.getFailureInfo() + ")");
    }

    /** Same text summary as the other versions. */
    public static String format(JobOutcome outcome) {
        StringBuilder sb = new StringBuilder("Status: " + outcome.status());
        outcome.log().forEach(line -> sb.append("\n- ").append(line));
        if ("published".equals(outcome.status())) {
            sb.append("\nPublished job_id: ").append(outcome.jobId());
        }
        return sb.toString();
    }
}
