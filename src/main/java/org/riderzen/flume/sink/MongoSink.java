package org.riderzen.flume.sink;

import java.text.*;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang.StringUtils;
import org.apache.flume.Channel;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.EventDeliveryException;
import org.apache.flume.Transaction;
import org.apache.flume.conf.Configurable;
import org.apache.flume.sink.AbstractSink;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.DateTimeFormatterBuilder;
import org.joda.time.format.DateTimeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.BasicDBObject;
import com.mongodb.BasicDBObjectBuilder;
import com.mongodb.CommandResult;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.Mongo;
import com.mongodb.MongoException;
import com.mongodb.WriteConcern;
import com.mongodb.util.JSON;

/**
 * Modify：by Frank.Zheng
 * Time:2016-06-13
 * Note:增加模式 auto_collection, 该模式自动获取event中的oid字段，作为集合名，即一个客户一张表，
 *      表名为客户的组织id。该模式下仍然需要指定db字段，即数据库名仍需要自己填写在flume.conf配置中
 */
 
/**
 * User: Frank.Zheng
 * Date: 16-6-13
 * Time: 上午11:43
 */
public class MongoSink extends AbstractSink implements Configurable {
    private static Logger logger = LoggerFactory.getLogger(MongoSink.class);

    private static DateTimeParser[] parsers = {
            DateTimeFormat.forPattern("yyyy-MM-dd").getParser(),
            DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss").getParser(),
            DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss.SSS").getParser(),
            DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss Z").getParser(),
            DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss.SSS Z").getParser(),
            DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ssZ").getParser(),
            DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ").getParser(),
            DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ssz").getParser(),
            DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSSz").getParser(),
    };
    public static DateTimeFormatter dateTimeFormatter = new DateTimeFormatterBuilder().append(null, parsers).toFormatter();

    public static final String HOST = "host";
    public static final String PORT = "port";
    public static final String AUTHENTICATION_ENABLED = "authenticationEnabled";
    public static final String USERNAME = "username";
    public static final String PASSWORD = "password";
    public static final String MODEL = "model";
    public static final String DB_NAME = "db";
    public static final String COLLECTION = "collection";
    public static final String NAME_PREFIX = "MongSink_";
    public static final String BATCH_SIZE = "batch";
    public static final String AUTO_WRAP = "autoWrap";
    public static final String WRAP_FIELD = "wrapField";
    public static final String TIMESTAMP_FIELD = "timestampField";
    public static final String TIMESTAMP_EVENT = "timestamp";
    public static final String OID = "oid";
    public static final String OPERATION = "op";
    public static final String PK = "_id";
    public static final String OP_INC = "$inc";
    public static final String OP_SET = "$set";
    public static final String OP_SET_ON_INSERT = "$setOnInsert";

    public static final boolean DEFAULT_AUTHENTICATION_ENABLED = false;
    public static final String DEFAULT_HOST = "localhost";
    public static final int DEFAULT_PORT = 27017;
    public static final String DEFAULT_DB = "events";
    public static final String DEFAULT_COLLECTION = "unknown_org_id";
    public static final int DEFAULT_BATCH = 100;
    private static final Boolean DEFAULT_AUTO_WRAP = false;
    public static final String DEFAULT_WRAP_FIELD = "log";
    public static final String DEFAULT_TIMESTAMP_FIELD = null;
    public static final char NAMESPACE_SEPARATOR = '.';
    public static final String OP_UPSERT = "upsert";
    public static final String EXTRA_FIELDS_PREFIX = "extraFields.";

    private static AtomicInteger counter = new AtomicInteger();

    private Mongo mongo;
    private DB db;

    private String host;
    private int port;
    private boolean authentication_enabled;
    private String username;
    private String password;
    private CollectionModel model;
    private String dbName;
    private String collectionName;
    private int batchSize;
    private boolean autoWrap;
    private String wrapField;
    private String timestampField;
    private final Map<String, String> extraInfos = new ConcurrentHashMap<String, String>();
    @Override
    public void configure(Context context) {
        setName(NAME_PREFIX + counter.getAndIncrement());

        host = context.getString(HOST, DEFAULT_HOST);
        port = context.getInteger(PORT, DEFAULT_PORT);
        authentication_enabled = context.getBoolean(AUTHENTICATION_ENABLED, DEFAULT_AUTHENTICATION_ENABLED);
        if (authentication_enabled) {
            username = context.getString(USERNAME);
            password = context.getString(PASSWORD);
        } else {
            username = "";
            password = "";
        }
        model = CollectionModel.valueOf(context.getString(MODEL, CollectionModel.single.name()));
        dbName = context.getString(DB_NAME, DEFAULT_DB);
        collectionName = context.getString(COLLECTION, DEFAULT_COLLECTION);
        batchSize = context.getInteger(BATCH_SIZE, DEFAULT_BATCH);
        autoWrap = context.getBoolean(AUTO_WRAP, DEFAULT_AUTO_WRAP);
        wrapField = context.getString(WRAP_FIELD, DEFAULT_WRAP_FIELD);
        timestampField = context.getString(TIMESTAMP_FIELD, DEFAULT_TIMESTAMP_FIELD);
        extraInfos.putAll(context.getSubProperties(EXTRA_FIELDS_PREFIX));
        logger.info("MongoSink {} context { host:{}, port:{}, authentication_enabled:{}, username:{}, password:{}, model:{}, dbName:{}, collectionName:{}, batch: {}, autoWrap: {}, wrapField: {}, timestampField: {} }",
                new Object[]{getName(), host, port, authentication_enabled, username, password, model, dbName, collectionName, batchSize, autoWrap, wrapField, timestampField});
    }

    @Override
    public synchronized void start() {
        logger.info("Starting {}...", getName());
        try {
            mongo = new Mongo(host, port);
            db = mongo.getDB(dbName);
        } catch (UnknownHostException e) {
            logger.error("Can't connect to mongoDB", e);
            return;
        }
        if (authentication_enabled) {
            boolean result = db.authenticate(username, password.toCharArray());
            if (result) {
                logger.info("Authentication attempt successful.");
            } else {
                logger.error("CRITICAL FAILURE: Unable to authenticate. Check username and Password, or use another unauthenticated DB. Not starting MongoDB sink.\n");
                return;
            }
        }
        super.start();
        logger.info("Started {}.", getName());
    }

    @Override
    public Status process() throws EventDeliveryException {
        logger.debug("{} start to process event", getName());

        Status status = Status.READY;
        try {
            status = parseEvents();
        } catch (Exception e) {
            logger.error("can't process events", e);
        }
        logger.debug("{} processed event", getName());
        return status;
    }
	
    private void saveEvents(Map<String, List<DBObject>> eventMap) {
        if (eventMap.isEmpty()) {
            logger.debug("eventMap is empty");
            return;
        }

        for (String eventCollection : eventMap.keySet()) {
            List<DBObject> docs = eventMap.get(eventCollection);
            if (logger.isDebugEnabled()) {
                logger.debug("collection: {}, length: {}", eventCollection, docs.size());
            }
            int separatorIndex = eventCollection.indexOf(NAMESPACE_SEPARATOR);
            String eventDb = eventCollection.substring(0, separatorIndex);
            String collectionName = eventCollection.substring(separatorIndex + 1);

            //Warning: please change the WriteConcern level if you need high datum consistence.
            DB db = mongo.getDB(eventDb);
            if (authentication_enabled) {
                boolean authResult = db.authenticate(username, password.toCharArray());
                if (!authResult) {
                    logger.error("Failed to authenticate user: " + username + " with password: " + password + ". Unable to write events.");
                    return;
                }
            }
			try {
				CommandResult result = db.getCollection(collectionName)
						.insert(docs, WriteConcern.SAFE).getLastError();
				if (result.ok()) {
					String errorMessage = result.getErrorMessage();
					if (errorMessage != null) {
						logger.error("can't insert documents with error: {} ",
								errorMessage);
						logger.error("with exception", result.getException());
						throw new MongoException(errorMessage);
					}
				} else {
					logger.error("can't get last error");
				}
			} catch (Exception e) {
				if (!(e instanceof com.mongodb.MongoException.DuplicateKey)) {
					logger.error("can't process event batch ", e);
				    logger.debug("can't process doc:{}", docs);
				}
				for (DBObject doc : docs) {
					try {
						db.getCollection(collectionName).insert(doc,
								WriteConcern.SAFE);
					} catch (Exception ee) {
						if (!(e instanceof com.mongodb.MongoException.DuplicateKey)) {
							logger.error(doc.toString());
							logger.error("can't process events, drop it!", ee);
						}
					}
				}
			}
		}
	}

    private Status parseEvents() throws EventDeliveryException {
        Status status = Status.READY;
        Channel channel = getChannel();
        Transaction tx = null;
        Map<String, List<DBObject>> eventMap = new HashMap<String, List<DBObject>>();
        Map<String, List<DBObject>> upsertMap = new HashMap<String, List<DBObject>>();
        try {
            tx = channel.getTransaction();
            tx.begin();

            for (int i = 0; i < batchSize; i++) {
                Event event = channel.take();
                if (event == null) {
                    status = Status.BACKOFF;
                    break;
                } else {
                    String operation = event.getHeaders().get(OPERATION);
                    if (logger.isDebugEnabled()) {
                        logger.debug("event operation is {}", operation);
                    }
                    if (OP_UPSERT.equalsIgnoreCase(operation)) {
                        processEvent(upsertMap, event);
                    } else if (StringUtils.isNotBlank(operation)) {
                        logger.error("non-supports operation {}", operation);
                    } else {
                        processEvent(eventMap, event);
                    }
                }
            }
            if (!eventMap.isEmpty()) {
                saveEvents(eventMap);
            }
            if (!upsertMap.isEmpty()) {
                doUpsert(upsertMap);
            }

            tx.commit();
        } catch (Exception e) {
            logger.error("can't process events, drop it!", e);
            if (tx != null) {
                tx.commit();// commit to drop bad event, otherwise it will enter dead loop.
            }

            throw new EventDeliveryException(e);
        } finally {
            if (tx != null) {
                tx.close();
            }
        }
        return status;
    }

    private void doUpsert(Map<String, List<DBObject>> eventMap) {
        if (eventMap.isEmpty()) {
            logger.debug("eventMap is empty");
            return;
        }

        for (String eventCollection : eventMap.keySet()) {
            List<DBObject> docs = eventMap.get(eventCollection);
            if (logger.isDebugEnabled()) {
                logger.debug("collection: {}, length: {}", eventCollection, docs.size());
            }
            int separatorIndex = eventCollection.indexOf(NAMESPACE_SEPARATOR);
            String eventDb = eventCollection.substring(0, separatorIndex);
            String collectionName = eventCollection.substring(separatorIndex + 1);

            //Warning: please change the WriteConcern level if you need high datum consistence.
            DB db = mongo.getDB(eventDb);
            if (authentication_enabled) {
                boolean authResult = db.authenticate(username, password.toCharArray());
                if (!authResult) {
                    logger.error("Failed to authenticate user: " + username + " with password: " + password + ". Unable to write events.");
                    return;
                }
            }
            DBCollection collection = db.getCollection(collectionName);
	    for (DBObject doc : docs) {
		if (logger.isDebugEnabled()) {
			logger.debug("doc: {}", doc);
		}
		DBObject query = BasicDBObjectBuilder.start()
				.add(PK, doc.get(PK)).get();
		BasicDBObjectBuilder doc_builder = BasicDBObjectBuilder.start();
		if (doc.keySet().contains(OP_INC)) {
			doc_builder.add(OP_INC, doc.get(OP_INC));
		}
		if (doc.keySet().contains(OP_SET)) {
			doc_builder.add(OP_SET, doc.get(OP_SET));
		}
		if (doc.keySet().contains(OP_SET_ON_INSERT)) {
			doc_builder.add(OP_SET_ON_INSERT, doc.get(OP_SET_ON_INSERT));
		}
		doc = doc_builder.get();
		//logger.debug("query: {}", query);
		//logger.debug("new doc: {}", doc);
		CommandResult result = collection.update(query, doc, true,
				false, WriteConcern.SAFE).getLastError();
		if (result.ok()) {
			String errorMessage = result.getErrorMessage();
			if (errorMessage != null) {
				logger.error("can't upsert documents with error: {} ",
						errorMessage);
				logger.error("with exception", result.getException());
				throw new MongoException(errorMessage);
			}
		} else {
		    logger.error("can't get last error");
		}
	    }
        }
    }

    private void processEvent(Map<String, List<DBObject>> eventMap, Event event) {
        switch (model) {
            case single:
                putSingleEvent(eventMap, event);

                break;
            case dynamic:
                putDynamicEvent(eventMap, event);
				
				break;
			case auto_collection:
                putAutoEvent(eventMap, event);
				
                break;
            default:
                logger.error("can't support model: {}, please check configuration.", model);
        }
    } 

	private void putAutoEvent(Map<String, List<DBObject>> eventMap, Event event) {
		DBObject eventJson;
        byte[] body = event.getBody();
        if (autoWrap) {
            eventJson = new BasicDBObject(wrapField, new String(body));
        } else {
            try {
                eventJson = (DBObject) JSON.parse(new String(body));
            } catch (Exception e) {
                logger.error("Can't parse events: " + new String(body), e);
				return;
            }
        }
		
		String collectionName = DEFAULT_COLLECTION;
		/*if (eventJson.containsField("timestamp")) {
			Long timestamp =  Long.valueOf(String.valueOf(eventJson.get("timestamp"))).longValue()*1000;
			collectionName = new java.text.SimpleDateFormat("yyyy-MM-dd").format(new Date(timestamp)); 
		} else {
			logger.info("............. no timestampField, use default collectionName: {}", collectionName);
		}*/
		
		if (eventJson.containsField(OID)) {
			collectionName = String.valueOf(eventJson.get(OID)); 
		} else {
			logger.info("............. no oid field, use default collectionName: {}", collectionName);
		}
		
        String eventCollection;
        eventCollection = dbName + NAMESPACE_SEPARATOR + collectionName;
        if (!eventMap.containsKey(eventCollection)) {
            eventMap.put(eventCollection, new ArrayList<DBObject>());
        }

        List<DBObject> docs = eventMap.get(eventCollection);
        addEventToList(docs, event);
    }
	
    private void putDynamicEvent(Map<String, List<DBObject>> eventMap, Event event) {
        String eventCollection;
        Map<String, String> headers = event.getHeaders();
        String eventDb = headers.get(DB_NAME);
        eventCollection = headers.get(COLLECTION);

        if (!StringUtils.isEmpty(eventDb)) {
            eventCollection = eventDb + NAMESPACE_SEPARATOR + eventCollection;
        } else {
            eventCollection = dbName + NAMESPACE_SEPARATOR + eventCollection;
        }

        if (!eventMap.containsKey(eventCollection)) {
            eventMap.put(eventCollection, new ArrayList<DBObject>());
        }

        List<DBObject> documents = eventMap.get(eventCollection);
        addEventToList(documents, event);
    }
	
    private void putSingleEvent(Map<String, List<DBObject>> eventMap, Event event) {
        String eventCollection;
        eventCollection = dbName + NAMESPACE_SEPARATOR + collectionName;
        if (!eventMap.containsKey(eventCollection)) {
            eventMap.put(eventCollection, new ArrayList<DBObject>());
        }

        List<DBObject> docs = eventMap.get(eventCollection);
        addEventToList(docs, event);
    }

    private List<DBObject> addEventToList(List<DBObject> documents, Event event) {
        if (documents == null) {
            documents = new ArrayList<DBObject>(batchSize);
        }

        DBObject eventJson;
        byte[] body = event.getBody();
        if (autoWrap) {
            eventJson = new BasicDBObject(wrapField, new String(body));
        } else {
            try {
                eventJson = (DBObject) JSON.parse(new String(body));
            } catch (Exception e) {
                logger.error("Can't parse events: " + new String(body), e);
                return documents;
            }
        }
        if (!event.getHeaders().containsKey(OPERATION) && timestampField != null) {
            Date timestamp;
            if (eventJson.containsField(timestampField)) {
                try {
                    String dateText = (String) eventJson.get(timestampField);
                    timestamp = dateTimeFormatter.parseDateTime(dateText).toDate();
                    eventJson.removeField(timestampField);
                } catch (Exception e) {
                    logger.error("can't parse date ", e);

                    timestamp = new Date();
                }
            } else {
                timestamp = new Date();
            }
            eventJson.put(timestampField, timestamp);
        }
        
        for(String key : extraInfos.keySet()) {
            eventJson.put(key, extraInfos.get(key));
        }
	
		// 2016-06-20,Frank.Zheng add:if model is auto_collection,remove the field 'oid'
		if (model == CollectionModel.auto_collection) {
			eventJson.removeField(OID);
		}
		
        documents.add(eventJson);

        return documents;
    }

    public enum CollectionModel {
        dynamic, single, auto_collection
    }
}