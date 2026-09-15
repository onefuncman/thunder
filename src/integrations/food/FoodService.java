package integrations.food;

import haven.*;
import haven.resutil.FoodInfo;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

public class FoodService {
    /**
     * Base URL for every request: the "Mapping URL" from Options, shared with the
     * automapper and the Cookbook. Read live (not captured at class-init) so an
     * endpoint pasted in after startup takes effect without a restart. Never null;
     * empty when unconfigured.
     */
    public static String endpoint() {
	String ep = CFG.AUTOMAP_ENDPOINT.get();
	if (ep == null) {
	    return "";
	}
	ep = ep.trim();
	while (ep.endsWith("/")) {
	    ep = ep.substring(0, ep.length() - 1);
	}
	return ep;
    }
    private static final String FOOD_DATA_URL = "/data/food-info.json";
    private static final File FOOD_DATA_CACHE_FILE = new File("food_data.json");
    private static String token = "KamiClient";
    
    private static final Map<String, ParsedFoodInfo> cachedItems = new ConcurrentHashMap<>();
    private static final Queue<HashedFoodInfo> sendQueue = new ConcurrentLinkedQueue<>();
    public static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    
    static {
	scheduler.execute(FoodService::loadCachedFoodData);
	scheduler.scheduleAtFixedRate(FoodService::sendItems, 10L, 10, TimeUnit.SECONDS);
	scheduler.scheduleAtFixedRate(FoodService::requestFoodDataCache, 0L, 30, TimeUnit.MINUTES);
    }
    
    /**
     * Load cached food data from the file (only keys for now since we don't use content anyway)
     */
    private static void loadCachedFoodData() {
	try {
	    if (FOOD_DATA_CACHE_FILE.exists()) {
		String jsonData = String.join("", Files.readAllLines(FOOD_DATA_CACHE_FILE.toPath(), StandardCharsets.UTF_8));
		//System.out.println("load from file: " + jsonData);
		JSONObject object = new JSONObject(jsonData);
		object.keySet().forEach(key -> cachedItems.put(key, new ParsedFoodInfo()));
		System.out.println("Loaded food data file: " + cachedItems.size() + " entries");
	    }
	} catch (Exception ex) {
	    System.err.println("Cannot load food data file: " + ex.getMessage());
	    try {
		Files.delete(FOOD_DATA_CACHE_FILE.toPath());
	    } catch (IOException e) {
		e.printStackTrace();
	    }
	}
    }
    
    /**
     * Check last modified for the food_data file and request update from server if too old
     */
    public static void requestFoodDataCache() {
	requestFoodDataCache(false);
    }

    /** Same, but {@code force} skips the freshness check. Errors are logged, never thrown. */
    public static void requestFoodDataCache(boolean force) {
	try {
	    fetchFoodData(force);
	} catch (Exception ex) {
	    System.err.println("Cannot load remote food data file: " + ex.getMessage());
	}
    }

    /**
     * The raw /data/food-info.json document (a JSON object keyed by record hash) --
     * the same download and on-disk cache the uploader uses for its dedup keys, so
     * the Cookbook doesn't need a fetch path of its own. Downloads when {@code force}
     * is set or the cached copy is missing/older than 30 minutes, otherwise returns
     * the cached copy. Throws instead of logging so a caller with a UI can show why.
     */
    public static synchronized String foodDataJson(boolean force) throws IOException {
	String fresh = fetchFoodData(force);
	if (fresh != null) {
	    return fresh;
	}
	return String.join("", Files.readAllLines(FOOD_DATA_CACHE_FILE.toPath(), StandardCharsets.UTF_8));
    }

    /**
     * Downloads the food data when forced or stale, updating the cache file and the
     * dedup keys. Returns the downloaded document, or null when the cached copy is
     * still fresh and nothing was fetched.
     */
    private static synchronized String fetchFoodData(boolean force) throws IOException {
	long lastModified = 0;
	if (FOOD_DATA_CACHE_FILE.exists()) {
	    lastModified = FOOD_DATA_CACHE_FILE.lastModified();
	}
	if (!force && System.currentTimeMillis() - lastModified <= TimeUnit.MINUTES.toMillis(30)) {
	    return null;
	}
	String ep = endpoint();
	if (ep.isEmpty()) {
	    throw new IOException("No Mapping URL configured (Options -> Mapping URL).");
	}
	HttpURLConnection connection = (HttpURLConnection) new URL(ep + FOOD_DATA_URL).openConnection();
	connection.setRequestProperty("Accept-Encoding", "gzip");
	connection.setRequestProperty("User-Agent", "H&H Client/" + token);
	connection.setRequestProperty("Cache-Control", "no-cache");
	StringBuilder stringBuilder = new StringBuilder();
	try (BufferedReader reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(connection.getInputStream())))) {
	    stringBuilder.append(reader.readLine());
	} finally {
	    connection.disconnect();
	}
	String content = stringBuilder.toString();
	//System.out.println("load from remote: " + content);
	JSONObject object;
	try {
	    object = new JSONObject(content);
	} catch (RuntimeException ex) {
	    throw new IOException("Endpoint did not return food data (" + ex.getMessage() + ")");
	}
	Files.write(FOOD_DATA_CACHE_FILE.toPath(), Collections.singleton(content), StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
	object.keySet().forEach(key -> cachedItems.put(key, new ParsedFoodInfo()));
	return content;
    }

    /**
     * Check item info and determine if it is food and we need to send it
     */
    public static void checkFood(List<ItemInfo> infoList, Resource res, double itemQ, String genus) {
//        if (!Resource.language.equals("en")) {
//            // Do not process localized items
//            return;
//        }
	try {
	    String resName = res.name;
	    FoodInfo foodInfo = ItemInfo.find(FoodInfo.class, infoList);
	    if (foodInfo != null) {
		//QualityList qBuff = ItemInfo.find(QualityList.class, infoList);
		double quality = itemQ; //qBuff.single().value;
		double multiplier = Math.sqrt(quality / 10.0);
		
		ParsedFoodInfo parsedFoodInfo = new ParsedFoodInfo();
		parsedFoodInfo.resourceName = resName;
		parsedFoodInfo.energy = (int) (Math.round(foodInfo.end * 100));
		parsedFoodInfo.hunger = round2Dig(foodInfo.glut * 1000 / Math.sqrt(multiplier));
		parsedFoodInfo.quality = quality;
		parsedFoodInfo.genus = genus;
		
		for (int i = 0; i < foodInfo.evs.length; i++) {
		    parsedFoodInfo.feps.add(new FoodFEP(foodInfo.evs[i].ev.nm, round2Dig(foodInfo.evs[i].a / multiplier)));
		}
		
		for (ItemInfo info : infoList) {
		    if (info instanceof ItemInfo.AdHoc) {
			String text = ((ItemInfo.AdHoc) info).str.text;
			// Skip food which base FEP's cannot be calculated
			if (text.equals("White-truffled")
			    || text.equals("Black-truffled")
			    || text.equals("Peppered")) {
			    return;
			}
		    }
		    if (info instanceof ItemInfo.Name) {
			parsedFoodInfo.itemName = ((ItemInfo.Name) info).str.text;
		    }

		    if (info.getClass().getName().contains("Ingredient")) {
			String name = (String) info.getClass().getField("name").get(info);
			Double value = (Double) info.getClass().getField("val").get(info);
			parsedFoodInfo.ingredients.add(new FoodIngredient(name, (int) (value * 100)));
		    } else if (info.getClass().getName().contains("Smoke")) {
			String name = (String) info.getClass().getField("name").get(info);
			Double value = (Double) info.getClass().getField("val").get(info);
			parsedFoodInfo.ingredients.add(new FoodIngredient(name, (int) (value * 100)));
		    }
		}
		
		checkAndSend(parsedFoodInfo);
	    }
	} catch (Exception ex) {
	    System.out.println("Cannot create food info: " + ex.getMessage());
	}
    }
    
    private static double round2Dig(double value) {
	return Math.round(value * 100.0) / 100.0;
    }
    
    private static void checkAndSend(ParsedFoodInfo info) {
	String hash = generateHash(info);
	if (cachedItems.containsKey(hash)) {
	    return;
	}
	sendQueue.add(new HashedFoodInfo(hash, info));
    }
    
    private static void sendItems() {
	if (sendQueue.isEmpty()) {
	    return;
	}
	
	Map<String, ParsedFoodInfo> toSend = new ConcurrentHashMap<String, ParsedFoodInfo>();
	while (!sendQueue.isEmpty()) {
	    HashedFoodInfo info = sendQueue.poll();
	    if (cachedItems.containsKey(info.hash)) {
		continue;
	    }
	    cachedItems.put(info.hash, info.foodInfo);
	    toSend.put(info.hash, info.foodInfo);
	}
	
	if (!toSend.isEmpty()) {
	    try {
		HttpURLConnection connection =
		    (HttpURLConnection) new URL(endpoint() + "/food").openConnection();
		connection.setRequestMethod("POST");
		connection.setRequestProperty("Content-Type", "application/json");
		connection.setRequestProperty("User-Agent", "H&H Client/" + token);
		connection.setDoOutput(true);
		try (OutputStream out = connection.getOutputStream()) {
		    // object.keySet().forEach(key -> cachedItems.put(key, new ParsedFoodInfo()));
		    JSONObject o = new JSONObject();
		    toSend.forEach((k,v) -> o.put(k, new JSONObject(v)));
		    //System.out.println(o.toString());
		    out.write(o.toString().getBytes(StandardCharsets.UTF_8));
		}
		StringBuilder stringBuilder = new StringBuilder();
		try (BufferedReader inputStream = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
		    stringBuilder.append(inputStream.readLine());
		}
		int code = connection.getResponseCode();
		if (code != 200) {
		    System.out.println("Response: " + stringBuilder.toString());
		}
		System.out.println("Sent " + toSend.size() + " food items, code: " + code);
	    } catch (Exception ex) {
		System.out.println("Cannot send " + toSend.size() + " food items, error: " + ex.getMessage());
	    }
	}
    }
    
    private static String generateHash(ParsedFoodInfo foodInfo) {
	try {
	    StringBuilder stringBuilder = new StringBuilder();
	    stringBuilder.append(foodInfo.itemName).append(";")
		.append(foodInfo.genus).append(";")
		.append(foodInfo.resourceName).append(";");
	    foodInfo.ingredients.forEach(it -> {
		stringBuilder.append(it.name).append(";").append(it.percentage).append(";");
	    });
	    
	    MessageDigest digest = MessageDigest.getInstance("MD5");
	    byte[] hash = digest.digest(stringBuilder.toString().getBytes(StandardCharsets.UTF_8));
	    return getHex(hash);
	} catch (NoSuchAlgorithmException e) {
	    System.out.println("Cannot generate food hash");
	}
	return null;
    }
    
    private static String getHex(byte[] bytes) {
	BigInteger bigInteger = new BigInteger(1, bytes);
	return bigInteger.toString(16);
    }
    
    private static class HashedFoodInfo {
	public String hash;
	public ParsedFoodInfo foodInfo;
	
	public HashedFoodInfo(String hash, ParsedFoodInfo foodInfo) {
	    this.hash = hash;
	    this.foodInfo = foodInfo;
	}
    }
    
    public static class FoodIngredient {
	private String name;
	private Integer percentage;
	
	public FoodIngredient(String name, Integer percentage) {
	    this.name = name;
	    this.percentage = percentage;
	}
	
	@Override
	public int hashCode() {
	    return Objects.hash(name, percentage);
	}
	
	public Integer getPercentage() {
	    return percentage;
	}
	
	public String getName() {
	    return name;
	}

    }
    
    public static class FoodFEP {
	private String name;
	private Double value;
	
	public FoodFEP(String name, Double value) {
	    this.name = name;
	    this.value = value;
	}
	
	@Override
	public int hashCode() {
	    return Objects.hash(name, value);
	}
	
	public String getName() {
	    return name;
	}
	
	public Double getValue() {
	    return value;
	}
    }
    
    public static class ParsedFoodInfo {
	public String genus;
	public String itemName;
	public String resourceName;
	public Integer energy;
	public double hunger;
	public double quality;
	public int version = 2;
	public ArrayList<FoodIngredient> ingredients;
	public ArrayList<FoodFEP> feps;
	
	public ParsedFoodInfo() {
	    this.itemName = "";
	    this.resourceName = "";
	    this.ingredients = new ArrayList<>();
	    this.feps = new ArrayList<>();
	}
	
	@Override
	public int hashCode() {
	    return Objects.hash(itemName, resourceName, ingredients);
	}
	
	public ArrayList<FoodFEP> getFeps() {
	    return feps;
	}
	
	public ArrayList<FoodIngredient> getIngredients() {
	    return ingredients;
	}
	
	public String getItemName() {
	    return itemName;
	}
	
	public String getResourceName() {
	    return resourceName;
	}
	
	public String getGenus() {
	    return genus;
	}
	
	public double getHunger() {
	    return hunger;
	}
	
	public Integer getEnergy() {
	    return energy;
	}
	public Integer getVersion() { return version; }
    }
}
