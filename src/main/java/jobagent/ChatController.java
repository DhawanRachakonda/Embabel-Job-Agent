package jobagent;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Chat backend for the React UI. Same contract as the other versions:
 * POST /chat {message, thread_id?} -> {reply, thread_id, done}.
 * Here {@code thread_id} is the Embabel agent process id.
 */
@RestController
public class ChatController {

    private final PipelineService pipeline;

    public ChatController(PipelineService pipeline) {
        this.pipeline = pipeline;
    }

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, Object> body) {
        try {
            String message = String.valueOf(body.getOrDefault("message", ""));
            Object threadId = body.get("thread_id");

            PipelineService.Reply reply = (threadId instanceof String id && !id.isBlank())
                    ? pipeline.answer(id, message)     // an open thread: this message is the answer
                    : pipeline.start(message);

            Map<String, Object> out = new LinkedHashMap<>();
            if (reply.paused()) {
                out.put("reply", reply.question());
                out.put("thread_id", reply.processId());
                out.put("done", false);
            } else {
                out.put("reply", PipelineService.format(reply.outcome()));
                out.put("thread_id", null);
                out.put("done", true);
            }
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }
}
