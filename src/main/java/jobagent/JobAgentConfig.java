package jobagent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JobAgentConfig {

    /** Only created when MONGO_URI is set; tests provide their own in-memory JobStore instead. */
    @Bean
    @ConditionalOnProperty(name = "MONGO_URI")
    JobStore jobStore(@Value("${MONGO_URI}") String mongoUri,
                      @Value("${job-agent.mongo.database}") String database,
                      @Value("${job-agent.mongo.collection}") String collection) {
        return new MongoJobStore(mongoUri, database, collection);
    }
}
