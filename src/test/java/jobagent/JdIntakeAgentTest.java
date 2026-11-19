package jobagent;

import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest;
import jobagent.JdDomain.ExtractedFields;
import jobagent.JdDomain.QualityScore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real Embabel platform (planner, actions) with the LLM mocked out.
 */
@SpringBootTest(classes = {JobAgentApplication.class, JdIntakeAgentTest.TestStore.class})
class JdIntakeAgentTest extends EmbabelMockitoIntegrationTest {

    @TestConfiguration
    static class TestStore {
        @Bean
        JobStore jobStore() {
            return new InMemoryJobStore();
        }
    }

    @Autowired
    PipelineService pipeline;

    @Autowired
    ChatController chat;

    /** The in-memory store is shared by every test, so each test uses its own title/company. */
    private static ExtractedFields job(String title, String company) {
        return new ExtractedFields(title, company, "Bangalore", "5+ years", "hybrid");
    }

    private void llmScores(int score) {
        whenCreateObject(p -> p.contains("Score this job"), QualityScore.class)
                .thenReturn(new QualityScore(score, "feedback"));
    }

    @Test
    void completeJdIsPublished() {
        whenCreateObject(p -> p.contains("Extract these fields"), ExtractedFields.class)
                .thenReturn(job("Backend Engineer", "Acme"));
        llmScores(90);

        var reply = pipeline.start("Senior Backend Engineer at Acme, Bangalore, 5+ years, hybrid.");

        assertFalse(reply.paused());
        assertEquals("published", reply.outcome().status());
        assertTrue(reply.outcome().jobId().startsWith("mem-"));
    }

    @Test
    void duplicateIsRejected() {
        whenCreateObject(p -> p.contains("Extract these fields"), ExtractedFields.class)
                .thenReturn(job("Dup Title", "DupCo"));
        llmScores(90);

        assertEquals("published", pipeline.start("jd one").outcome().status());
        assertEquals("rejected", pipeline.start("jd two").outcome().status());
    }

    @Test
    void missingFieldsPauseForTheRecruiterThenContinue() {
        whenCreateObject(p -> p.contains("Extract these fields"), ExtractedFields.class).thenReturn(
                new ExtractedFields("QA Engineer", "Initech", "", "", ""));
        llmScores(90);

        var reply = pipeline.start("We need a QA engineer at Initech.");
        assertEquals("What is the location?", reply.question());

        reply = pipeline.answer(reply.processId(), "Hyderabad");
        assertEquals("What is the exp?", reply.question());
        reply = pipeline.answer(reply.processId(), "4 years");
        assertEquals("What is the work_mode?", reply.question());
        reply = pipeline.answer(reply.processId(), "onsite");

        assertFalse(reply.paused());
        assertEquals("published", reply.outcome().status());
        assertTrue(reply.outcome().log().stream().anyMatch(l -> l.contains("got 'exp'")));
    }

    @Test
    void poorQualityRewritesTwiceThenEscalates() {
        whenCreateObject(p -> p.contains("Extract these fields"), ExtractedFields.class).thenReturn(job("Vague Role", "Hooli"));
        llmScores(40);
        whenGenerateText(p -> p.contains("Rewrite this job")).thenReturn("A clearer rewritten posting.");

        var reply = pipeline.start("a vague jd");

        assertEquals("needs_input", reply.outcome().status());
        assertEquals(2, reply.outcome().log().stream().filter(l -> l.startsWith("rewrite_jd")).count());
    }

    @Test
    @SuppressWarnings("unchecked")
    void chatEndpointContract() {
        whenCreateObject(p -> p.contains("Extract these fields"), ExtractedFields.class)
                .thenReturn(new ExtractedFields("Data Analyst", "Umbrella", "", "2 years", "remote"));
        llmScores(90);

        var first = chat.chat(java.util.Map.of("message", "Data analyst at Umbrella")).getBody();
        assertEquals(false, first.get("done"));
        assertEquals("What is the location?", first.get("reply"));

        var last = chat.chat(java.util.Map.of("message", "Pune", "thread_id", first.get("thread_id"))).getBody();
        assertEquals(true, last.get("done"));
        assertTrue(((String) last.get("reply")).startsWith("Status: published"));
        assertEquals(null, last.get("thread_id"));
    }
}
