package jobagent;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import org.bson.Document;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;

/**
 * MongoDB-backed store. Equality on lowercase fields, no regex. The database and collection are configurable so that
 * several apps can share one cluster without sharing a collection (the duplicate check would see each other's jobs).
 */
public class MongoJobStore implements JobStore {

    private final String mongoUri;
    private final String dbName;
    private final String collectionName;
    private MongoClient client;

    public MongoJobStore(String mongoUri, String dbName, String collectionName) {
        if (mongoUri == null || mongoUri.isBlank()) {
            throw new IllegalStateException(
                    "MONGO_URI is not set. Add your MongoDB Atlas connection string to the environment (see .env.example).");
        }
        this.mongoUri = mongoUri;
        this.dbName = dbName;
        this.collectionName = collectionName;
    }

    private synchronized MongoCollection<Document> collection() {
        if (client == null) {
            client = MongoClients.create(mongoUri);
        }
        return client.getDatabase(dbName).getCollection(collectionName);
    }

    @Override
    public Optional<String> findDuplicate(String title, String company) {
        Document existing = collection().find(and(
                eq("title_lower", title.strip().toLowerCase()),
                eq("company_lower", company.strip().toLowerCase()))).first();
        return Optional.ofNullable(existing).map(d -> d.getObjectId("_id").toHexString());
    }

    @Override
    public String publish(Map<String, String> parsed, String description, int qualityScore) {
        Document doc = new Document(parsed)
                .append("title_lower", parsed.get("title").strip().toLowerCase())
                .append("company_lower", parsed.get("company").strip().toLowerCase())
                .append("description", description)
                .append("quality_score", qualityScore)
                .append("date_posted", LocalDate.now().toString());
        return collection().insertOne(doc).getInsertedId().asObjectId().getValue().toHexString();
    }
}
