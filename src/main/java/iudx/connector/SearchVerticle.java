package iudx.connector;

import java.io.FileInputStream;
import java.io.InputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.mongo.FindOptions;
import io.vertx.ext.mongo.MongoClient;
import org.bson.json.JsonParseException;

public class SearchVerticle extends AbstractVerticle {

	private static final Logger logger = LoggerFactory.getLogger(SearchVerticle.class);
	private static String options;
	private static String resource_group_id;
	private static String resource_id;
	private static String time;
	private static String[] timeStamp;
	private static String startTime;
	private static String endTime;
	private static String TRelation; 
	private long allowed_number_of_days;
	private JsonObject query, isotime, dateTime, startDateTime, endDateTime;
	private Instant instant, startInstant, endInstant;
	private TimeZone tz;
	private DateFormat df; 
	private Calendar now;
	private String nowAsISO;
	
	private MongoClient mongo;
	private JsonObject	mongoconfig;
	private String 		database_host;
	private int 		database_port;
	private String 		database_user;
	private String 		database_password;
	private String 		database_name;
	private String 		auth_database;
	private String 		connectionStr;
	private static final String COLLECTION = "archive";
	private JsonObject resourceQuery, attributeQuery, temporalQuery, finalGeoQuery, finalQuery;
	private boolean geo_attribute_query = false;
    
    private String geometry="", relation="", coordinatesS="";
    private String[] coordinatesArr;
    private JsonArray coordinates;
    private JsonArray expressions;
    private HashMap<String, Double> frequencyOfEmitting;
	Set<String> itemsWithProviderName = new HashSet<String>();
	Set<String> items = new HashSet<String>();
	ItemsSingleton itemsSingleton;
	private boolean isGroupQuery=false;
	@Override
	public void start() throws Exception {
		logger.info("Search Verticle started!");

		vertx.eventBus().consumer("search", message -> {
			search(message);
		});

		Properties prop = new Properties();
	    InputStream input = null;

	    try 
	    {
	        input = new FileInputStream("config.properties");
	        prop.load(input);
	        
	        database_user		=	prop.getProperty("database_user");
	        database_password	=	prop.getProperty("database_password");
	        database_host 		=	prop.getProperty("database_host");
	        database_port		=	Integer.parseInt(prop.getProperty("database_port"));
	        database_name		=	prop.getProperty("database_name");
	        auth_database		=	prop.getProperty("auth_database");
	        	        

	        logger.debug("database_user 	: " + database_user);
	        logger.debug("database_password	: " + database_password);
	        logger.debug("database_host 	: " + database_host);
	        logger.debug("database_port 	: " + database_port);
	        logger.debug("database_name		: " + database_name);
	        logger.debug("auth_database		: " + auth_database);
	        
	        
	        input.close();
	        
	    } 
	    catch (Exception e) 
	    {
	        e.printStackTrace();
	    } 
	    
		mongoconfig		= 	new JsonObject()
						.put("username", database_user)
						.put("password", database_password)
						.put("authSource", auth_database)
						.put("host", database_host)
						.put("port", database_port)
						.put("db_name", database_name);

		mongo = MongoClient.createShared(vertx, mongoconfig);

		tz = TimeZone.getTimeZone("UTC");
		df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"); 
		df.setTimeZone(tz);

		frequencyOfEmitting=new HashMap<>();
		frequencyOfEmitting.put("aqm-bosch-climo", 0.25);
		frequencyOfEmitting.put("flood-sensor",0.25);
		frequencyOfEmitting.put("wifi-hotspot",0.25);
		frequencyOfEmitting.put("streetlight-feeder-sree",0.25);
		frequencyOfEmitting.put("pune-itms",0.02);
		//frequencyOfEmitting.put("tomtom",0.25);
		//frequencyOfEmitting.put("safetipin",0.67);
		//frequencyOfEmitting.put("changebhai",0.67);
		frequencyOfEmitting.put("pune-iitm-aqi",1d);
		frequencyOfEmitting.put("pune-iitm-forecast",1d);

		//Varanasi Resource Groups
		frequencyOfEmitting.put("varanasi-swm-vehicles",1d);
		frequencyOfEmitting.put("varanasi-aqm",0.17);
		frequencyOfEmitting.put("varanasi-swm-bins",0.17);
		frequencyOfEmitting.put("varanasi-swm-workers",0.5);
		frequencyOfEmitting.put("varanasi-iudx-gis",0d);
		frequencyOfEmitting.put("varanasi-citizen-app",6d);

		try{
			itemsSingleton=ItemsSingleton.getInstance();
			itemsWithProviderName=itemsSingleton.getItems();
			for(String s: itemsWithProviderName){
				String s1[]=s.split("/");
				String s2=s;
				if(s1.length == 5)
					s2=s1[2]+"/"+s1[3]+"/"+s1[4];
				items.add(s2);
			}
			logger.info("(SEARCH VERTICLE)**** Items Set contains "+items.size()+ " items");
		}catch (Exception e){
			logger.info("(SEARCH VERTICLE)**** Items Set- "+e.getMessage());
		}
	}

	private void search(Message<Object> message) {

		int state = Integer.parseInt(message.headers().get("state"));
		options = message.headers().get("options");
		JsonObject requested_body = new JsonObject(message.body().toString());

		logger.info("state : " + state);
		logger.info("requested_body : " + requested_body);
		logger.info("options : " + options);
		JsonObject query = constructQuery(state, message);

		logger.info(query.toString());

		if(query.containsKey("allowed_number_of_days")) {
			message.reply(query);
		} else if(query.containsKey("time")) {
			message.reply(query);
		} else if(query.containsKey("geo-issue")) {
			message.reply(query);
		} else if(query.containsKey("static")){
			message.fail(0, "Static data does not support latest query");
		}
		else {
		JsonObject fields = new JsonObject();

		if (requested_body.containsKey("attribute-filter")) {
			System.out.println(requested_body.getJsonArray("attribute-filter"));
			JsonArray attributeFilter = requested_body.getJsonArray("attribute-filter");
			for (int i = 0; i < attributeFilter.size(); i++) {
				String field = attributeFilter.getString(i);
				if (field.charAt(0) == '$') {
					field = "_$_" + field.substring(1);
				}
				fields.put(field, 1);
			}
		}
		searchDatabase(state, "archive", query, fields, message);
		}
	}

	private JsonObject constructQuery(int state, Message<Object> message) {

		JsonObject request = (JsonObject) message.body();
		query = new JsonObject();
		finalQuery = new JsonObject();
		resourceQuery = new JsonObject();
		isotime = new JsonObject();
		try{
			resource_group_id = request.getString("resource-group-id");
			resourceQuery.put("__resource-group",resource_group_id);
			resource_id = request.getString("resource-id");
			if(resource_group_id.equalsIgnoreCase(resource_id) && "latest".equalsIgnoreCase(request.getString("options"))){
				//group query
				if("varanasi-iudx-gis".equalsIgnoreCase(resource_group_id)) {
					logger.info("*** SEARCH-VERTICLE === Queried latest for static GIS data");
					return new JsonObject().put("static",true) ;
				}else {
					logger.info("*** SEARCH-VERTICLE === Group API call");
					isGroupQuery=true;
					String currentTimeISO = df.format(Calendar.getInstance().getTime());
					Instant currentInstant= Instant.parse(currentTimeISO);
					//allowing group queries not older than a day ie. 24 hours
					//Can be adjusted as per requirement
					Instant queryMinTime=currentInstant.minus(Duration.ofHours(24));
					resourceQuery.put("__time", new JsonObject()
							.put("$gt",new JsonObject()
									.put("$date",queryMinTime)));
				}
				//return resourceQuery;
			}
			else
				resourceQuery.put("__resource-id",resource_id);
		}catch (Exception e){
			logger.error("Error: "+ e.getMessage());
		}
		geo_attribute_query = false;
		
		if("pune-itms".equalsIgnoreCase(resource_group_id)) {
			allowed_number_of_days = 1;
		} else {
			allowed_number_of_days = 30;
		}
	
		switch (state) {
		case 1:
			options = request.getString("options");
			if(isGroupQuery || !(request.containsKey("group")))
				return resourceQuery;
			else{
				query.put("__resource-group",resource_group_id);
				return query;
			}

		case 2:
			options = request.getString("options");
			query=resourceQuery;
			return query;

		case 3:
//			query = constructTimeSeriesQuery(request);
//			break;

		case 4:
			query = constructTimeSeriesQuery(request);
			break;

		case 5:
//			if(!isGroupQuery && "within".equalsIgnoreCase(request.getString("relation")))
//				query = constructGeoCircleQuery(request);
//			else
//				return resourceQuery;
//			break;

		case 6:
			query = constructGeoCircleQuery(request);
			break;

        case 7:
			query = constructGeoBboxQuery(request);
            break;

        case 8:
            query = constructGeoPoly_Line_PointQuery(request);
            break;
        
        case 9:
            query = constructGeoBboxQuery(request);
            break;
        
        case 10:
            query = constructGeoPoly_Line_PointQuery(request);
            break;

		case 11:
//			query = constructAttributeQuery(request);
//			break;

		case 12:
			query = constructAttributeQuery(request);
			break;
		}
		
		if(query.containsKey("time")) {
			finalQuery = query;
		}
		
		else if(query.containsKey("allowed_number_of_days")) {
			finalQuery = query;
		} 
		
		else if(query.containsKey("geo-issue")) {
			finalQuery = query;
		}
		
		else {
			expressions = new JsonArray();

			if(!isGroupQuery && ! request.containsKey("time") && ! request.containsKey("options"))
			{
				JsonObject timeQuery = new JsonObject();

				now = Calendar.getInstance();
				nowAsISO = df.format(now.getTime()); 

				endInstant = Instant.parse(nowAsISO);
				endDateTime = new JsonObject();
				endDateTime.put("$date", endInstant);
				
				startInstant = endInstant.minus(Duration.ofDays(allowed_number_of_days));
				startDateTime = new JsonObject();
				startDateTime.put("$date", startInstant);

				isotime.put("$gte", startDateTime);
				isotime.put("$lte", endDateTime);
				timeQuery.put("__time", isotime);

				expressions.add(resourceQuery).add(query).add(timeQuery);
				finalQuery.put("$and", expressions);
			}
			else 
			{
				expressions.add(resourceQuery).add(query);
			}
			
			finalQuery.put("$and", expressions);
			System.out.println("FINAL QUERY: " + finalQuery.toString());
		}
		return finalQuery;
	}

	double MetersToDecimalDegrees(double meters, double latitude) {
		return meters / (111.32 * 1000 * Math.cos(latitude * (Math.PI / 180)));
	}

	private JsonObject constructTimeSeriesQuery(JsonObject request) {

		resource_group_id = request.getString("resource-group-id");
		resource_id = request.getString("resource-id");
		time = request.getString("time");
		TRelation = request.getString("TRelation");
		JsonObject attributeQuery = new JsonObject();
		JsonObject timeQuery = new JsonObject();
		JsonArray expressions = new JsonArray();
		
		boolean isvalid = checkTimeStampValidity(TRelation, time);
		
		if(isvalid) {
			
		if (TRelation.contains("during")) {

			timeStamp = time.split("/");
			startTime = timeStamp[0];
			endTime = timeStamp[1];
			
			if(startTime.contains("Z")) 
			{
				startInstant = Instant.parse(startTime);
			}
			
			else 
			{
				OffsetDateTime start = OffsetDateTime.parse( startTime );
				startInstant = start.toInstant();
			}
			
			startDateTime = new JsonObject();
			startDateTime.put("$date", startInstant);
			
			if(endTime.contains("Z")) 
			{
				endInstant = Instant.parse(endTime);
			}
			
			else 
			{
				OffsetDateTime end = OffsetDateTime.parse( endTime );
				endInstant = end.toInstant();
			}
	
			endDateTime = new JsonObject();
			endDateTime.put("$date", endInstant);
			
			if(ChronoUnit.DAYS.between(startInstant, endInstant) > allowed_number_of_days)
			{
				timeQuery.put("allowed_number_of_days", allowed_number_of_days);
			}
			else 
			{
				isotime.put("$gte", startDateTime);
				isotime.put("$lte", endDateTime);
				timeQuery.put("__time", isotime);
			}
		}

		else if (TRelation.contains("before")) {

			if(time.contains("Z")) 
			{
				instant = Instant.parse(time);
			}
			
			else 
			{
				OffsetDateTime start = OffsetDateTime.parse( time );
				instant = start.toInstant();
			}

			startInstant = instant.minus(Duration.ofDays(allowed_number_of_days));
			startDateTime = new JsonObject();
			startDateTime.put("$date", startInstant);
			
			dateTime = new JsonObject();
			dateTime.put("$date", instant);

			isotime.put("$gte", startDateTime);
			isotime.put("$lte", dateTime);
			timeQuery.put("__time", isotime);
		}

		else if (TRelation.contains("after")) {
			
			if(time.contains("Z")) 
			{
				instant = Instant.parse(time);
			}
			
			else 
			{
				OffsetDateTime start = OffsetDateTime.parse( time );
				instant = start.toInstant();
			}

			dateTime = new JsonObject();
			dateTime.put("$date", instant);
			
			endInstant = instant.plus(Duration.ofDays(allowed_number_of_days));
			endDateTime = new JsonObject();
			endDateTime.put("$date", endInstant);
		
			isotime.put("$gte", dateTime);
			isotime.put("$lte", endDateTime);
			timeQuery.put("__time", isotime);
		}

		else if (TRelation.contains("TEquals")) {

			if(time.contains("Z")) 
			{
				instant = Instant.parse(time);
			}
			
			else 
			{
				OffsetDateTime start = OffsetDateTime.parse( time );
				instant = start.toInstant();
			}

			dateTime = new JsonObject();
			dateTime.put("$date", instant);

			//query.put("__resource-id", resource_id);
			isotime.put("$eq", dateTime);
			query.put("__time", isotime);
		}

		if(request.containsKey("attribute-name") && request.containsKey("attribute-value") && ! geo_attribute_query){
			attributeQuery = constructAttributeQuery(request);
			expressions.add(timeQuery).add(attributeQuery);
			query.put("$and",expressions);
			logger.info("TIME QUERY + ATTRIBUTE QUERY");
		}else
			query = timeQuery;

		System.out.println(query);
		
		}
		else {
			timeQuery.put("time", "in-valid");
			query = timeQuery;
		}
		return query;

	}
	
	private boolean checkTimeStampValidity(String TRelation, String time) {		boolean isvalid = false;
		
		if( TRelation.equalsIgnoreCase("during") || TRelation.equalsIgnoreCase("before") || TRelation.equalsIgnoreCase("after") ) {
			// continue
			if(TRelation.equalsIgnoreCase("during")) {
				if(! time.contains("/")) {
					// Respond with 400
					isvalid = false;
				} else {
					// check start and end time
					isvalid = true;
				}
				
			} else if(TRelation.equalsIgnoreCase("before")) {
				if(time.contains("/")) {
					// Respond with 400
					isvalid = false;
				} else {
					// check time
					isvalid = true;
				}
			} else if(TRelation.equalsIgnoreCase("after")) {
				if(time.contains("/")) {
					// Respond with 400
					isvalid = false;
				} else {
					// check time
					isvalid = true;
				}
			}
		} else {
			// Respond with 400
			isvalid = false;
		}
		return isvalid;
	}

	private JsonObject constructGeoCircleQuery(JsonObject request) {
		double latitude=0.0, longitude=0.0, rad;
		boolean attribute = false, temporal = false;
		geometry = "circle";
		query = new JsonObject();
        attributeQuery = new JsonObject();
        temporalQuery = new JsonObject();
        finalGeoQuery = new JsonObject();
        expressions = new JsonArray(); 
		try{
			latitude = request.containsKey("lat")?Double.parseDouble(request.getString("lat")):0.0;
			longitude = request.containsKey("lon")?Double.parseDouble(request.getString("lon")):0.0;
			relation= request.containsKey("relation")?request.getString("relation"):"intersects";
			boolean valid = validateRelation(geometry, relation); 
			if (!valid) {
				finalGeoQuery.put("geo-issue", "in-valid relation");
				return finalGeoQuery;
			}
		}catch (Exception e){
			logger.info("SEARCH_VERTICLE/constructGeoCircleQuery: "+ e.getMessage());
			finalGeoQuery.put("geo-issue", "in-valid query");
			return finalGeoQuery;
		}

		if(isGroupQuery)
			relation="within";

        if ("within".equalsIgnoreCase(relation)){
        	/**
			 * Query GeoWithin format-
			 * {__geoJsonLocation: {$geoWithin: {$centreSphere: [lon,lat,rad]}}}
			 * */
			rad = request.containsKey("radius")?(Double.parseDouble(request.getString("radius")) / (6378.1*1000)):0;
        	query.put("__geoJsonLocation", new JsonObject().put("$geoWithin", new JsonObject().put("$centerSphere",
					new JsonArray().add(new JsonArray().add(longitude).add(latitude)).add(rad))));
        }
        else if("intersects".equalsIgnoreCase(relation)){

        	/**
			 * Query NearSphere format-
			 * {__geoJsonLocation: {$nearSphere: {$geometry: {type: Point, coordinates: [lon,lat]}, $maxDistance: rad}}}
			 * */
			rad = request.containsKey("radius")?(Double.parseDouble(request.getString("radius"))):0;
        	query.put("__geoJsonLocation", new JsonObject().put("$nearSphere",
					new JsonObject().put("$geometry",new JsonObject().put("type","Point")
													.put("coordinates",new JsonArray().add(longitude).add(latitude)))
							.put("$maxDistance",rad)));
		}

        if (request.containsKey("attribute-name") && request.containsKey("attribute-value")){
			attributeQuery = constructAttributeQuery(request);
			attribute = true;
		}

		if (request.containsKey("time") && request.containsKey("TRelation")) { 
			geo_attribute_query = true;
			temporalQuery = constructTimeSeriesQuery(request);
			if (temporalQuery.containsKey("allowed_number_of_days")) {
				temporal = false;
				finalGeoQuery = temporalQuery;
				return finalGeoQuery;
			} else if (temporalQuery.containsKey("time")) {
				temporal = false;
				finalGeoQuery = temporalQuery;
				return finalGeoQuery;
			} else {
				temporal = true;
			}
		}

		if (attribute && temporal) {
			expressions.add(query).add(attributeQuery).add(temporalQuery);
			finalGeoQuery.put("$and", expressions);
		} else if (attribute) {
			expressions.add(query).add(attributeQuery);
			finalGeoQuery.put("$and", expressions);
		} else if (temporal) {
			expressions.add(query).add(temporalQuery);
			finalGeoQuery.put("$and", expressions);
		} else {
			finalGeoQuery = query;
		}

		return finalGeoQuery;

	}

    private JsonObject constructGeoBboxQuery(JsonObject request){
        geometry="bbox";
        JsonObject geoQuery = new JsonObject();
		expressions = new JsonArray();
		query = new JsonObject();
		coordinates = new JsonArray();
        relation = request.containsKey("relation")?request.getString("relation").toLowerCase():"intersects";
        boolean valid = validateRelation(geometry, relation);
		boolean attribute = false, temporal = false;
        attributeQuery = new JsonObject();
        temporalQuery = new JsonObject();
        finalGeoQuery = new JsonObject();

        if(valid){
            coordinatesS = request.getString("bbox");
            coordinatesArr = coordinatesS.split(",");
            JsonArray temp = new JsonArray();
            JsonArray y1x1 = new JsonArray().add(getDoubleFromS(coordinatesArr[1])).add(getDoubleFromS(coordinatesArr[0]));
            JsonArray y1x2 = new JsonArray().add(getDoubleFromS(coordinatesArr[1])).add(getDoubleFromS(coordinatesArr[2]));
            JsonArray y2x2 = new JsonArray().add(getDoubleFromS(coordinatesArr[3])).add(getDoubleFromS(coordinatesArr[2]));
            JsonArray y2x1 = new JsonArray().add(getDoubleFromS(coordinatesArr[3])).add(getDoubleFromS(coordinatesArr[0]));
            temp.add(y1x1).add(y1x2).add(y2x2).add(y2x1).add(y1x1);
            coordinates.add(temp);
            geoQuery = buildGeoQuery("Polygon",coordinates,relation);
        
		} else {
			finalGeoQuery.put("geo-issue", "in-valid query");
			return finalGeoQuery;
		}

        query=geoQuery;
        
        if (request.containsKey("attribute-name") && request.containsKey("attribute-value")){
			attributeQuery = constructAttributeQuery(request);
			attribute = true;
		}
        
		if (request.containsKey("time") && request.containsKey("TRelation")) {
			geo_attribute_query = true;
			temporalQuery = constructTimeSeriesQuery(request);
			if (temporalQuery.containsKey("allowed_number_of_days")) {
				temporal = false;
				finalGeoQuery = temporalQuery;
				return finalGeoQuery;
			} else if (temporalQuery.containsKey("time")) {
				temporal = false;
				finalGeoQuery = temporalQuery;
				return finalGeoQuery;
			} else {
				temporal = true;
			}
		}

		if (attribute && temporal) {
			expressions.add(query).add(attributeQuery).add(temporalQuery);
			finalGeoQuery.put("$and", expressions);
		} else if (attribute) {
			expressions.add(query).add(attributeQuery);
			finalGeoQuery.put("$and", expressions);
		} else if (temporal) {
			expressions.add(query).add(temporalQuery);
			finalGeoQuery.put("$and", expressions);
		} else {
			finalGeoQuery = query;
		}
        
        return finalGeoQuery;
    }

    private JsonObject constructGeoPoly_Line_PointQuery(JsonObject request){

		JsonObject geoQuery = new JsonObject();
		expressions = new JsonArray();
		coordinates = new JsonArray();
		boolean attribute = false, temporal = false;
        attributeQuery = new JsonObject();
        temporalQuery = new JsonObject();
        finalGeoQuery = new JsonObject();
        
        //Polygon or LineString
        if(request.containsKey("geometry")){
            if(request.getString("geometry").toUpperCase().contains("Polygon".toUpperCase()))
                geometry = "Polygon";
        	else if(request.getString("geometry").toUpperCase().contains("lineString".toUpperCase()))
            	geometry = "LineString";
        	else if(request.getString("geometry").toUpperCase().contains("Point".toUpperCase()))
				geometry = "Point";
        	else {
        		finalGeoQuery.put("geo-issue", "in-valid query");
				return finalGeoQuery;
        	}
        }

        relation = request.containsKey("relation")?request.getString("relation").toLowerCase():"intersects";
        boolean valid = validateRelation(geometry, relation);
        if(valid){
            switch(geometry){
                case "Polygon":
                    coordinatesS = request.getString("geometry");
                    coordinatesS = coordinatesS.replaceAll("[a-zA-Z()]","");
                    coordinatesArr = coordinatesS.split(",");
                    JsonArray extRing = new JsonArray();
                    for (int i = 0 ; i<coordinatesArr.length;i+=2){
                        JsonArray points = new JsonArray();
                        points.add(getDoubleFromS(coordinatesArr[i+1])).add(getDoubleFromS(coordinatesArr[i]));
                        extRing.add(points);
                    }
                    coordinates.add(extRing);
                    System.out.println("QUERY: " + coordinates.toString());
                    geoQuery = buildGeoQuery(geometry,coordinates,relation);
                    break;

                case "LineString":
                    coordinatesS = request.getString("geometry");
                    coordinatesS = coordinatesS.replaceAll("[a-zA-Z()]","");
                    coordinatesArr = coordinatesS.split(",");
                    for (int i = 0 ; i<coordinatesArr.length;i+=2){
                        JsonArray points = new JsonArray();
                        points.add(getDoubleFromS(coordinatesArr[i+1])).add(getDoubleFromS(coordinatesArr[i]));
                        coordinates.add(points);
                    }
                    geoQuery = buildGeoQuery(geometry,coordinates,relation);
                    break;

                case "Point":
					coordinatesS = request.getString("geometry");
					coordinatesS = coordinatesS.replaceAll("[a-zA-Z()]","");
					coordinatesArr = coordinatesS.split(",");
					coordinates.add(getDoubleFromS(coordinatesArr[1])).add(getDoubleFromS(coordinatesArr[0]));
					geoQuery=buildGeoQuery(geometry,coordinates,relation);
					break;
			}
		} else {
			finalGeoQuery.put("geo-issue", "in-valid query");
			return finalGeoQuery;
		}

        query = geoQuery;

		if (request.containsKey("attribute-name") && request.containsKey("attribute-value")){
			attributeQuery = constructAttributeQuery(request);
			attribute = true;
		}
        
		if (request.containsKey("time") && request.containsKey("TRelation")) {
			geo_attribute_query = true;
			temporalQuery = constructTimeSeriesQuery(request);
			if (temporalQuery.containsKey("allowed_number_of_days")) {
				temporal = false;
				finalGeoQuery = temporalQuery;
				return finalGeoQuery;
			} else if (temporalQuery.containsKey("time")) {
				temporal = false;
				finalGeoQuery = temporalQuery;
				return finalGeoQuery;
			} else {
				temporal = true;
			}
		}

		if (attribute && temporal) {
			expressions.add(query).add(attributeQuery).add(temporalQuery);
			finalGeoQuery.put("$and", expressions);
		} else if (attribute) {
			expressions.add(query).add(attributeQuery);
			finalGeoQuery.put("$and", expressions);
		} else if (temporal) {
			expressions.add(query).add(temporalQuery);
			finalGeoQuery.put("$and", expressions);
		} else {
			finalGeoQuery = query;
		}

        return finalGeoQuery;
    }

	private JsonObject constructAttributeQuery(JsonObject request){

		JsonObject query = new JsonObject();
		String attribute_name = request.getString("attribute-name");
		String attribute_value = request.getString("attribute-value");
		String comparison_operator;
		comparison_operator=request.getString("comparison-operator").toLowerCase();
		System.out.println("ATTRIBUTE SEARCH");
		Double attr_v_n=0.0;
		if(isNumeric(attribute_value))
			attr_v_n=Double.parseDouble(attribute_value);

			switch (comparison_operator){

				case "propertyisequalto":
					query.put(attribute_name,attribute_value);
					break;

				case "propertyisnotequalto":
					query = numericQuery(attribute_name,attr_v_n,"$ne");
					break;

				case "propertyislessthan":
					query = numericQuery(attribute_name,attr_v_n,"$lt");
					break;

				case "propertyisgreaterthan":
					query = numericQuery(attribute_name,attr_v_n,"$gt");
					break;

				case "propertyislessthanequalto":
					query = numericQuery(attribute_name,attr_v_n,"$lte");
					break;

				case "propertyisgreaterthanequalto":
					query = numericQuery(attribute_name,attr_v_n,"$gte");
					break;

				case "propertyislike":
					query.put(attribute_name,new JsonObject().put("$regex",attribute_value)
																.put("$options","i"));
					break;

				case "propertyisbetween":
					String[] attr_arr = attribute_value.split(",");
					query.put("$expr",new JsonObject()
							.put("$and",new JsonArray().add(new JsonObject()
									.put("$gt",new JsonArray().add(new JsonObject().put("$convert", new JsonObject().put("input","$"+attribute_name)
											.put("to","double")
											.put("onError","No numeric value available (NA/Unavailable)")
											.put("onNull","No value available")))
											.add(getDoubleFromS(attr_arr[0]))))
									.add(new JsonObject()
											.put("$lt",new JsonArray().add(new JsonObject().put("$convert", new JsonObject().put("input","$"+attribute_name)
													.put("to","double")
													.put("onError","No numeric value available (NA/Unavailable)")
													.put("onNull","No value available")))
													.add(getDoubleFromS(attr_arr[1]))))));

					break;

			}
		return query;
	}

	/**
	 * Helper function to determine if the attribute-value has a numeric value as String
	 **/

	private boolean isNumeric(String s){

		try {
				double d = Double.parseDouble(s);
		}catch (NumberFormatException | NullPointerException e){
			return false;
		}
		return true;
	}

    private JsonObject buildGeoQuery(String geometry, JsonArray coordinates, String relation){

	JsonObject query = new JsonObject();

	switch(relation){

		case "equals": query = new JsonObject()
						.put("__geoJsonLocation.coordinates",coordinates );
				break;

		case "disjoint": break;

		case "touches":
		case "overlaps":
		case "crosses":
		case "contains":
		case "intersects": query = searchGeoIntersects(geometry,coordinates);
				            break;

		case "within": query = searchGeoWithin(geometry,coordinates);
				        break;

        default: break;
	}

    return query;
  }

  /**
   * Helper function to convert string values to Double
   */
  private Double getDoubleFromS(String s){
    Double d = Double.parseDouble(s);
    return d;
  }

  /**
   * Helper function to generate query in mongo to compare Numeric values encoded
   * as Strings with Numbers
   **/
  private JsonObject numericQuery(String attrName, Double attrValue, String comparisonOp){

  	JsonObject query = new JsonObject();
  	query.put("$expr", new JsonObject()
						.put(comparisonOp,new JsonArray()
									.add(new JsonObject()
											.put("$convert", new JsonObject().put("input","$"+attrName)
																.put("to","double")
																.put("onError","No numeric value available (NA/Unavailable)")
																.put("onNull","No value available")))
									.add(attrValue)));
  	return query;
  }


  /**
   * Performs Mongo-GeoIntersects operation
   * */

  private JsonObject searchGeoIntersects(String geometry, JsonArray coordinates){

	JsonObject query = new JsonObject();

	query.put("__geoJsonLocation", new JsonObject()
				.put("$geoIntersects", new JsonObject()
					.put("$geometry",new JsonObject()
						.put("type",geometry)
						.put("coordinates",coordinates))));
	System.out.println("GeoIntersects: "+query.toString());
    return query;
  }

  /**
   * Performs Mongo-GeoWithin operation
   * */

  private JsonObject searchGeoWithin(String geometry, JsonArray coordinates){

    JsonObject query = new JsonObject();
    query.put("__geoJsonLocation", new JsonObject()
            .put("$geoWithin", new JsonObject()
                .put("$geometry",new JsonObject()
                    .put("type",geometry)
                    .put("coordinates",coordinates))));
    System.out.println("GeoWithin: " + query.toString());
    return query;
  }

  /**
   *Helper function to validate relation for specified geometry.
   */

  private boolean validateRelation(String geometry, String relation){

	if(geometry.equalsIgnoreCase("bbox") && (relation.equalsIgnoreCase("equals")
							||relation.equalsIgnoreCase("disjoint")
							|| relation.equalsIgnoreCase("touches")
							|| relation.equalsIgnoreCase("overlaps")
							|| relation.equalsIgnoreCase("crosses")
							|| relation.equalsIgnoreCase("intersects")
							|| relation.equalsIgnoreCase("within") )){

		return true;
	}

    else if(geometry.equalsIgnoreCase("linestring") && (relation.equalsIgnoreCase("equals")
							||relation.equalsIgnoreCase("disjoint")
							|| relation.equalsIgnoreCase("touches")
							|| relation.equalsIgnoreCase("overlaps")
							|| relation.equalsIgnoreCase("crosses")
							|| relation.equalsIgnoreCase("intersects") )){

		return true;
	}
	else if(geometry.equalsIgnoreCase("polygon") && (relation.equalsIgnoreCase("equals")
							||relation.equalsIgnoreCase("disjoint")
							|| relation.equalsIgnoreCase("touches")
							|| relation.equalsIgnoreCase("overlaps")
							|| relation.equalsIgnoreCase("crosses")
							|| relation.equalsIgnoreCase("intersects")
							|| relation.equalsIgnoreCase("within") )){

		return true;
	}

	else if(geometry.equalsIgnoreCase("point") && (relation.equalsIgnoreCase("equals")
			||relation.equalsIgnoreCase("disjoint")
			|| relation.equalsIgnoreCase("touches")
			|| relation.equalsIgnoreCase("overlaps")
			|| relation.equalsIgnoreCase("crosses")
			|| relation.equalsIgnoreCase("intersects"))){

		return true;
	}

	else if(geometry.equalsIgnoreCase("circle") && (relation.equalsIgnoreCase("within")
			|| relation.equalsIgnoreCase("intersects"))){

		return true;
	}
	
    else
	   return false;

  }

	private void searchDatabase(int state, String COLLECTION, JsonObject query, JsonObject attributeFilter,
			Message<Object> message) {

		JsonObject sortFilter;
		FindOptions findOptions;
		String api;
		JsonObject request_body = (JsonObject) message.body();
		System.out.println(request_body);
		String resGroupName=request_body.getString("resource-group-id");
		switch (state) {

		case 1:
			attributeFilter.put("_id", 0);

			sortFilter = new JsonObject();
			sortFilter.put("__time", -1);

			findOptions = new FindOptions();
			findOptions.setFields(attributeFilter);
			findOptions.setLimit(1);
			findOptions.setSort(sortFilter);

			if (options.contains("latest")) {
				api = "latest";
				if(isGroupQuery)
					mongoGroupAgg(resGroupName,query,attributeFilter,message);

				else
					mongoFind(api, state, COLLECTION, query, findOptions, message);
			}

			else if (options.contains("status")) {
				api = "status";
				String nowAsISO = ZonedDateTime.now( ZoneId.of("Asia/Kolkata") ).format( DateTimeFormatter.ISO_INSTANT );
                allowed_number_of_days = 2;
                instant = Instant.parse(nowAsISO);

                startDateTime = new JsonObject();
                startDateTime.put("$date", instant);

                endInstant = instant.minus(Duration.ofDays(allowed_number_of_days));
                endDateTime = new JsonObject();
                endDateTime.put("$date", endInstant);

                isotime.put("$gte", endDateTime);
                isotime.put("$lte", startDateTime);

                JsonObject timeQuery = new JsonObject();
                timeQuery.put("__time", isotime);

                System.out.println(timeQuery);
                expressions = new JsonArray();
                expressions.add(query).add(timeQuery);

                finalQuery.put("$and", expressions);
                attributeFilter.put("_id", 0);

                sortFilter = new JsonObject();
                sortFilter.put("__time", -1);

                findOptions = new FindOptions();
                findOptions.setFields(attributeFilter);
                findOptions.setLimit(1);
                findOptions.setSort(sortFilter);

				if(request_body.containsKey("group") && request_body.getBoolean("group")){
					mongoAggStatus(resGroupName, COLLECTION, query, message);
				}else
				mongoFind(api, state, COLLECTION, finalQuery, findOptions, message);
			}

			break;

		case 2:
		case 3:
			api = "search";
			attributeFilter.put("_id", 0);
			sortFilter = new JsonObject();
			sortFilter.put("__time", -1);

			findOptions = new FindOptions();
			findOptions.setFields(attributeFilter);
			findOptions.setSort(sortFilter);
			if(request_body.containsKey("options")) {
				if(request_body.getString("options").equalsIgnoreCase("latest")) {
					findOptions.setLimit(1);
				} else {
					message.fail(1, "invalid-options");
					break;
				}
			}
			mongoFind(api, state, COLLECTION, query, findOptions, message);
			break;

		case 4:
			api = "count";
			mongoCount(state, COLLECTION, query, message);
			break;

		case 5:
			api = "search";
			attributeFilter.put("_id", 0);
			if(isGroupQuery){
				mongoGroupAgg(resGroupName,query,attributeFilter,message);
			}else {
				sortFilter = new JsonObject();
				sortFilter.put("__time", -1);

				findOptions = new FindOptions();
				findOptions.setFields(attributeFilter);
				findOptions.setSort(sortFilter);
				if(request_body.containsKey("options")) {
					if(request_body.getString("options").equalsIgnoreCase("latest")) {
						findOptions.setLimit(1);
					} else {
						message.fail(1, "invalid-options");
						break;
					}
				}
				mongoFind(api, state, COLLECTION, query, findOptions, message);
			}
			break;

		case 6:
			api = "count";
			mongoCount(state, COLLECTION, query, message);
			break;

		case 7:
			api="search";
			attributeFilter.put("_id", 0);
			if(isGroupQuery){
				mongoGroupAgg(resGroupName,query,attributeFilter,message);
			}else {
				sortFilter = new JsonObject();
				sortFilter.put("__time", -1);
				findOptions = new FindOptions();
				findOptions.setFields(attributeFilter);
				findOptions.setSort(sortFilter);
				if (request_body.containsKey("options")) {
					if (request_body.getString("options").equalsIgnoreCase("latest")) {
						findOptions.setLimit(1);
					} else {
						message.fail(1, "invalid-options");
						break;
					}
				}
				mongoFind(api, state, COLLECTION, query, findOptions, message);
			}
			break;

		case 8:
			api="search";
			attributeFilter.put("_id", 0);
			if(isGroupQuery){
				mongoGroupAgg(resGroupName,query,attributeFilter,message);
			}else {
				sortFilter = new JsonObject();
				sortFilter.put("__time", -1);
				findOptions = new FindOptions();
				findOptions.setFields(attributeFilter);
				findOptions.setSort(sortFilter);
				if (request_body.containsKey("options")) {
					if (request_body.getString("options").equalsIgnoreCase("latest")) {
						findOptions.setLimit(1);
					} else {
						message.fail(1, "invalid-options");
						break;
					}
				}
				mongoFind(api, state, COLLECTION, query, findOptions, message);
			}
			break;

		case 9:
			api="count";
			mongoCount(state,COLLECTION,query,message);
			break;

		case 10:
			api="count";
			mongoCount(state,COLLECTION,query,message);
			break;

		case 11:
			api="search";
			attributeFilter.put("_id", 0);
			if(isGroupQuery){
				mongoGroupAgg(resGroupName,query,attributeFilter,message);
			}else {
				JsonObject obj = (JsonObject) message.body();
				String requestOptions = obj.containsKey("options") ? obj.getString("options") : null;
				findOptions = new FindOptions();
				if (requestOptions != null) {
					JsonObject sortFil = new JsonObject().put("__time", -1);
					findOptions.setSort(sortFil);
					findOptions.setLimit(1);
				}
				findOptions.setFields(attributeFilter);
				mongoFind(api, state, COLLECTION, query, findOptions, message);
			}
			break;

		case 12:
			api="count";
			mongoCount(state,COLLECTION,query,message);
			break;
		}
	}

//	private void mongoGroupGeoCircle(String resGroupName,JsonObject attributeFilter, Message<Object> message){
//		Set<String> idFromGroup= new HashSet<>();
//		for(String item: items){
//			if(item.matches("(.*)"+resGroupName+"(.*)"))
//				idFromGroup.add(item);
//		}
//		int batchSize=idFromGroup.isEmpty()?220:idFromGroup.size();
//		double latitude=0.0, longitude=0.0, rad=0.0;
//
//		JsonObject request= (JsonObject) message.body();
//		try{
//			latitude = request.containsKey("lat")?Double.parseDouble(request.getString("lat")):0.0;
//			longitude = request.containsKey("lon")?Double.parseDouble(request.getString("lon")):0.0;
//			relation= request.containsKey("relation")?request.getString("relation"):"intersects";
//			boolean valid = validateRelation(geometry, relation);
//			if (!valid) {
//				message.fail(0,"Invalid Relation!");
//
//			}
//		}catch (Exception e){
//			logger.info("SEARCH_VERTICLE/constructGeoCircleQuery: "+ e.getMessage());
//			message.fail(0,"Invalid GEO-Query!");
//
//		}
//		JsonArray coordinates=new JsonArray().add(longitude).add(latitude);
//		rad=request.containsKey("radius")?(Double.parseDouble(request.getString("radius"))):0;
//		String currentTimeISO = df.format(Calendar.getInstance().getTime());
//		Instant currentInstant= Instant.parse(currentTimeISO);
//		//allowing group queries not older than a day ie. 24 hours
//		//Can be adjusted as per requirement
//		Instant queryMinTime=currentInstant.minus(Duration.ofHours(24));
//		JsonObject query=new JsonObject().put("__resource-group",resGroupName)
//				.put("__time", new JsonObject()
//				.put("$gt",new JsonObject()
//						.put("$date",queryMinTime)));
//		JsonObject geoNearStage=new JsonObject()
//				.put("$geoNear", new JsonObject()
//						.put("near", new JsonObject()
//								.put("Point",coordinates))
//						.put("distanceField", "distanceFromPoint")
//						.put("maxDistance",rad)
//						.put("query", query))
//						.put("spherical",true);
//		JsonObject groupStage=new JsonObject()
//				.put("$group", new JsonObject()
//						.put("_id","$__resource-id")
//						.put("LUT", new JsonObject()
//								.put("$max","$__time"))
//						.put("doc",new JsonObject()
//								.put("$first","$$ROOT")));
//		if(attributeFilter.size()==0)
//			attributeFilter.put("_id",0)
//					.put("__resource-id",0)
//					.put("__time",0);
//		else
//			attributeFilter.put("_id",0);
//		JsonObject projectStage=new JsonObject()
//				.put("$project",attributeFilter);
//		JsonObject replacementStage=new JsonObject()
//				.put("$replaceRoot", new JsonObject()
//						.put("newRoot","$doc"));
//		JsonArray aggPipeline=new JsonArray()
//				.add(geoNearStage)
//				.add(groupStage)
//				.add(replacementStage)
//				.add(projectStage);
//		JsonObject aggregationCommand=new JsonObject()
//				.put("aggregate", COLLECTION)
//				.put("pipeline",aggPipeline)
//				.put("cursor", new JsonObject().put("batchSize",batchSize));
//		logger.info("Tis Aggregation Query "+ aggregationCommand.toString());
//		mongo.runCommand("aggregate", aggregationCommand, res->{
//			if(res.succeeded() && !idFromGroup.isEmpty()) {
//				//Set<String> itemsFromDB = new HashSet();
//				logger.info("GROUP AGG QUERY:========"+ res.result().toString());
//				JsonArray result = res.result().getJsonObject("cursor").getJsonArray("firstBatch");
//				JsonArray response = new JsonArray();
//				if(!result.isEmpty()) {
//					for (Object o : result) {
//						JsonObject j = (JsonObject) o;
//						response.add(j);
//					}
//					logger.info("Latest (Resource Group) aggregation query was successful with "+ response.size()+" documents returned.");
//				}
//				else{
//					logger.info("Aggregation for latest Group API: The result is empty");
//				}
//				message.reply(response);
//			}
//			else{
//				logger.error("Database query has FAILED!!! "+ res.cause());
//				message.fail(1, "item-not-found");
//			}
//
//		});
//	}
	private void mongoGroupAgg(String resGroupName,JsonObject query, JsonObject attributeFilter, Message<Object> message) {
		Set<String> idFromGroup= new HashSet<>();
//		double latitude=0.0, longitude=0.0, rad=0.0;
		JsonObject matchStage=new JsonObject();
		JsonArray aggPipeline=new JsonArray();
//		JsonObject geoNearStage = new JsonObject();

  		for(String item: items){
			if(item.matches("(.*)"+resGroupName+"(.*)"))
				idFromGroup.add(item);
		}
		int batchSize=idFromGroup.isEmpty()?220:idFromGroup.size();
		//boolean isCircleIntersect=false;
  		//match query is a similar to mongo.find() queries

//		JsonObject request= (JsonObject) message.body();
//		if(request.containsKey("lat")&&request.containsKey("lon")
//				&&request.containsKey("relation")&& "intersects".equalsIgnoreCase(request.getString("relation"))) {
//			logger.info("***** SEARCH-VERTICLE ===== Geo Circle Intersect Group API");
//			isCircleIntersect=true;
//			try{
//				latitude = Double.parseDouble(request.getString("lat"));
//				longitude = Double.parseDouble(request.getString("lon"));
//				relation= request.getString("relation");
//				boolean valid = validateRelation("circle", relation);
//				if (!valid) {
//					logger.info("SEARCH_VERTICLE/constructGeoCircleQuery Invalid relation");
//					message.fail(0,"Invalid Relation!");
//				}
//			}catch (Exception e){
//				logger.info("SEARCH_VERTICLE/constructGeoCircleQuery: "+ e.getMessage());
//				message.fail(0,"Invalid GEO-Query!");
//
//			}
//			JsonArray coordinates=new JsonArray().add(longitude).add(latitude);
//			rad=request.containsKey("radius")?(Double.parseDouble(request.getString("radius"))):0;
//			geoNearStage.put("$geoNear", new JsonObject()
//							.put("near", new JsonObject()
//									.put("type", "Point")
//									.put("coordinates",coordinates))
//							.put("distanceField", "distanceFromPoint")
//							.put("maxDistance", rad)
//							.put("query", query)
//							.put("spherical", true));
//
//			aggPipeline.add(geoNearStage);
//		}
//		if(!isCircleIntersect) {
//			matchStage.put("$match", query);
//			aggPipeline.add(matchStage);
//		}
		matchStage.put("$match", query);
		aggPipeline.add(matchStage);

  		JsonObject groupStage=new JsonObject()
				.put("$group", new JsonObject()
						.put("_id","$__resource-id")
						.put("LUT", new JsonObject()
								.put("$max","$__time"))
  						.put("doc",new JsonObject()
								.put("$first","$$ROOT")));

  		logger.info("ATTRIBUTE FILTER SIZE############# "+ attributeFilter.toString());
  		if(attributeFilter.size()==1)
  			attributeFilter.put("__time",0);

  		JsonObject projectStage=new JsonObject()
				.put("$project",attributeFilter);

  		JsonObject replacementStage=new JsonObject()
				.put("$replaceRoot", new JsonObject()
						.put("newRoot","$doc"));

  		aggPipeline.add(groupStage).add(replacementStage).add(projectStage);

		JsonObject aggregationCommand=new JsonObject()
				.put("aggregate", COLLECTION)
				.put("pipeline",aggPipeline)
				.put("cursor", new JsonObject().put("batchSize",batchSize));

		logger.info("Tis Aggregation Query "+ aggregationCommand.toString());
		mongo.runCommand("aggregate", aggregationCommand, res->{
			if(res.succeeded() && !idFromGroup.isEmpty()) {
				//Set<String> itemsFromDB = new HashSet();
				logger.info("GROUP AGG QUERY:========"+ res.result().toString());
				JsonArray result = res.result().getJsonObject("cursor").getJsonArray("firstBatch");
				JsonArray response = new JsonArray();
				if(!result.isEmpty()) {
					for (Object o : result) {
						JsonObject j = (JsonObject) o;
						response.add(j);
					}
					logger.info("Latest (Resource Group) aggregation query was successful with "+ response.size()+" documents returned.");
				}
				else{
					logger.info("Aggregation for latest Group API: The result is empty");
				}
				message.reply(response);
			}
			else{
				logger.error("Database query has FAILED!!! "+ res.cause());
				message.fail(1, "item-not-found");
			}

		});
	}

	private void mongoAggStatus(String resGroupName, String COLLECTION, JsonObject query, Message<Object> message) {
		double requiredFreq=frequencyOfEmitting.get(resGroupName);
		double maxTime=frequencyOfEmitting.get(resGroupName)*100;
		String currentTimeISO = df.format(Calendar.getInstance().getTime());
		Instant currentInstant= Instant.parse(currentTimeISO);
		Instant queryMinTime=currentInstant.minus(Duration.ofHours((long)maxTime));
		Set<String> idFromGroup= new HashSet<>();

		for(String item: items){
			if(item.matches("(.*)"+resGroupName+"(.*)"))
				idFromGroup.add(item);
		}

		logger.info("(SEARCH VERTILCE)**** Items in Group contains "+idFromGroup.size()+" items");
		int batchSize=idFromGroup.isEmpty()?485:idFromGroup.size();

		JsonObject matchStage=new JsonObject()
				.put("$match", new JsonObject()
								.put("__resource-group",resGroupName)
								.put("__time", new JsonObject()
												.put("$gt",new JsonObject()
															.put("$date",queryMinTime))));
  		JsonObject sortStage=new JsonObject()
				.put("$sort", new JsonObject()
								.put("__time",-1));

  		JsonObject groupStage=new JsonObject()
				.put("$group", new JsonObject()
									.put("_id","$__resource-id")
									.put("LUT", new JsonObject()
												.put("$first","$__time")));
  		JsonArray aggPipeline=new JsonArray()
				.add(matchStage)
				.add(sortStage)
				.add(groupStage);
		JsonObject aggregationCommand=new JsonObject()
				.put("aggregate", COLLECTION)
				.put("pipeline",aggPipeline)
				.put("cursor", new JsonObject().put("batchSize",batchSize));

		logger.info("Tis Aggregation Query "+ aggregationCommand.toString());
		mongo.runCommand("aggregate", aggregationCommand, res->{
			if(res.succeeded() && !idFromGroup.isEmpty()) {
				Set<String> itemsFromDB = new HashSet();
				JsonArray result = res.result().getJsonObject("cursor").getJsonArray("firstBatch");
				JsonArray response = new JsonArray();
				if(!result.isEmpty()) {
					DateTimeFormatter format = DateTimeFormatter.ISO_DATE_TIME;
					for (Object o : result) {
						JsonObject j = (JsonObject) o;
						JsonObject status = new JsonObject();
						String res_name = j.getString("_id");
						JsonObject lastUpdatedTime = j.getJsonObject("LUT");
						String time = lastUpdatedTime.getString("$date");
						LocalDateTime sensedDateTime = null;
						try {
							sensedDateTime = LocalDateTime.parse(time, format);
						} catch (Exception ex) {
							status.put(res_name, "date-issue");
							response.add(status);
							message.reply(response);
							break;
						}
						LocalDateTime currentDateTime = LocalDateTime.now(ZoneOffset.UTC);
						long timeDifference = Duration.between(sensedDateTime, currentDateTime).toHours();


						itemsFromDB.add(res_name);
						if (timeDifference <= requiredFreq * 8) {
							status.put(res_name, "live");
						} else if (timeDifference > requiredFreq * 8 && timeDifference <= requiredFreq * 24) {
							status.put(res_name, "recently-live");
						} else if (timeDifference > requiredFreq * 24 && timeDifference <= requiredFreq * 48) {
							status.put(res_name, "recently-active");
						} else {
							status.put(res_name, "down");
						}
						response.add(status);
					}

					logger.info("Status (Resource Group) aggregation query was successful with "+ response.size()+" documents returned.");
				}
				else{
					logger.info("Aggregation for Status API: The result is empty");
				}
				idFromGroup.removeAll(itemsFromDB);
				for (String s : idFromGroup) {
					JsonObject status = new JsonObject();
					status.put(s, "down");
					response.add(status);
				}
				message.reply(response);
			}
			else{
				logger.error("Database query has FAILED!!! "+ res.cause());
				message.fail(1, "item-not-found");
			}

		});
	}

	private void mongoFind(String api, int state, String COLLECTION, JsonObject query, FindOptions findOptions,
			Message<Object> message) {
		// String[] hiddenFields = { "__resource-id", "__time", "_id", "__resource-group" };
		String[] hiddenFields = { "__time", "_id"};
		JsonObject requested_body = new JsonObject(message.body().toString());
		final long starttime = System.currentTimeMillis();
		mongo.findWithOptions(COLLECTION, query, findOptions, database_response -> {
			if (database_response.succeeded()) {
				JsonArray response = new JsonArray();
				//DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS[XXX][X]");
				DateTimeFormatter format = DateTimeFormatter.ISO_DATE_TIME;

				if (database_response.result().isEmpty() && api.equalsIgnoreCase("status")) {
					JsonObject status = new JsonObject();
					status.put("status", "down");
					response.add(status);
					message.reply(response);
				}
				
				for (JsonObject j : database_response.result()) {

					if (api.equalsIgnoreCase("status")) {
						JsonObject status = new JsonObject();
						JsonObject __time = j.getJsonObject("__time");
						String time = __time.getString("$date");
						LocalDateTime sensedDateTime = null;
						try {
							sensedDateTime = LocalDateTime.parse(time, format);
						} catch (Exception ex) {
							status.put("status", "date-issue");
							response.add(status);
							message.reply(response);
							break;
						}
						LocalDateTime currentDateTime = LocalDateTime.now(ZoneOffset.UTC);
						long timeDifference = Duration.between(sensedDateTime, currentDateTime).toHours();
/***
						logger.info("Last Status Update was at : " + sensedDateTime.toString());
						logger.info("Current Time is : " + currentDateTime.toString());
						logger.info("Time Difference is : " + timeDifference);
**/
							if (timeDifference <= 8) {
								status.put("status", "live");
							} else if (timeDifference > 8 && timeDifference <= 24) {
								status.put("status", "recently-live");
							} else if (timeDifference > 24 && timeDifference <= 48) {
								status.put("status", "recently-active");
							} else {
								status.put("status", "down");
							}
						response.add(status);

					}
					
					else if(requested_body.containsKey("token")) {


						for (String hidden : hiddenFields) {
							if (j.containsKey(hidden)) {
								j.remove(hidden);
							}
						}
						if(j.containsKey("Images"))
						{
							String images = j.getString("Images");
							String[] image_array = images.split(";");
							JsonArray json_image_array = new JsonArray();
							for (int i = 0; i < image_array.length; i++) {
								json_image_array.add(image_array[i]);
							}
							j.remove("Images");
							j.put("Images", json_image_array);
						}

						response.add(j);
						
					}

					else {

						for (String hidden : hiddenFields) {
							if (j.containsKey(hidden)) {
								j.remove(hidden);
							}
						}

						if(j.containsKey("Images"))
						{
						   j.remove("Images");
						}
						
						response.add(j);
					}
				}

				logger.info("Database Reply is : " + database_response.result().toString());
				logger.info("Response is : " + response.toString());
				final long endtime = System.currentTimeMillis();
				logger.info("Query time is : " + (endtime - starttime));
				message.reply(response);

			} else {
				logger.info("Database query has FAILED!!!");
				message.fail(1, "item-not-found");
			}
		});

	}

	private void mongoCount(int state, String COLLECTION, JsonObject query, Message<Object> message) {
		mongo.count(COLLECTION, query, database_response -> {
			if (database_response.succeeded()) {

				JsonObject response = new JsonObject();
				long numItems = database_response.result();
				response.put("count", numItems);

				logger.info("Database Reply is : " + database_response.result().toString());
				logger.info("Response is : " + response.toString());

				message.reply(response);

			} else {
				message.fail(1, "item-not-found");
			}
		});
	}

}
