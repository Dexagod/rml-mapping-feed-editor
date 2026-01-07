package demo;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.*;

public final class RmlMapAndPost {
  
  // RMLMapper CLI entrypoint class (inside the rmlmapper dependency)
  private static final String RMLMAPPER_MAIN = "be.ugent.rml.cli.Main";
  
  public static void main(String[] args) throws Exception {
   
    Map<String, List<String>> a = parseArgsMulti(args);

    URI inputUrl         = URI.create(require(a, "--inputUrl"));
    String mapping       = getSingle(a, "--mapping");
    URI postUrl          = URI.create(require(a, "--postUrl"));
    String serialization = getSingleOrDefault(a, "--serialization", "turtle");
    String bearerToken   = getSingle(a, "--bearer"); // optional

    URI feedUrl          = URI.create(require(a, "--feedUrl"));
    String title         = getSingle(a, "--title"); // optional
    String description   = getSingle(a, "--description"); // optional

    List<String> keywords   = getMulti(a, "--keywords");
    List<String> ontologies = getMulti(a, "--ontologies");
    List<String> shapes     = getMulti(a, "--shapes");

    System.err.println("inputUrl=" + inputUrl);
    System.err.println("mapping=" + mapping);
    System.err.println("postUrl=" + postUrl);
    System.err.println("feedUrl=" + feedUrl);
    System.err.println("keywords=" + keywords);
    System.err.println("ontologies=" + ontologies);
    System.err.println("shapes=" + shapes);


    
    Path tempMapping = Files.createTempFile("mapping-", ".ttl");
    Files.writeString(tempMapping, mapping, StandardCharsets.UTF_8);
    
    // 2) Run RMLMapper in-process, capture stdout RDF
    byte[] rdfBytes = runRmlMapperCaptureStdout(tempMapping, serialization);
    
    // 3) POST to server
    String contentType = contentTypeFor(serialization);
    
    HttpClient client = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(20))
    .build();
    // ... inside main(), after rdfBytes/contentType/client are built:
    
    HttpRequest.Builder req = HttpRequest.newBuilder()
    .uri(postUrl)
    .timeout(Duration.ofMinutes(2))
    .header("Content-Type", contentType)
    .POST(HttpRequest.BodyPublishers.ofByteArray(rdfBytes));
    
    if (bearerToken != null && !bearerToken.isBlank()) {
      req.header("Authorization", "Bearer " + bearerToken);
    }
    
    HttpResponse<byte[]> resp = client.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
    
    System.err.println("POST " + postUrl + " -> HTTP " + resp.statusCode());
    
    // if (resp.body() != null && !resp.body().isBlank()) {
    //   System.err.println(resp.body());
    // }
    
    // Extract Location header (case-insensitive), resolve relative URI if needed
    URI locationUri = extractLocationUri(resp, postUrl);
    
    if (locationUri == null) {
      System.err.println("No Location header returned; skipping updateFeed().");
      return;
    }
    
    System.err.println("Location: " + locationUri);
    String updateString = createUpdateString(
      locationUri,
      inputUrl,
      title,
      description,
      keywords,
      ontologies,
      shapes,
      feedUrl
    );

    // Start feed update process: GET -> append updateString -> PUT back
    updateFeed(client, feedUrl, bearerToken, updateString);
    
    // cleanup
    Files.deleteIfExists(tempMapping);
    
  }
  
  private static Map<String, List<String>> parseArgsMulti(String[] args) {
    Map<String, List<String>> m = new LinkedHashMap<>();

    for (int i = 0; i < args.length; i++) {
      String k = args[i];
      if (!k.startsWith("--")) continue;

      String v = (i + 1 < args.length && !args[i + 1].startsWith("--"))
          ? args[++i]
          : "true";

      m.computeIfAbsent(k, __ -> new ArrayList<>()).add(v);
    }
    return m;
  }

  private static String require(Map<String, List<String>> a, String key) {
    String v = getSingle(a, key);
    if (v == null || v.isBlank()) {
      throw new IllegalArgumentException("Missing required arg: " + key);
    }
    return v;
  }

  private static String getSingle(Map<String, List<String>> a, String key) {
    // Case-insensitive key match
    for (var e : a.entrySet()) {
      if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) {
        List<String> vals = e.getValue();
        return (vals == null || vals.isEmpty()) ? null : vals.get(0);
      }
    }
    return null;
  }

  private static String getSingleOrDefault(Map<String, List<String>> a, String key, String defaultValue) {
    String v = getSingle(a, key);
    return (v == null || v.isBlank()) ? defaultValue : v;
  }

  private static List<String> getMulti(Map<String, List<String>> a, String key) {
    List<String> raw = null;

    // Case-insensitive key match
    for (var e : a.entrySet()) {
      if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) {
        raw = e.getValue();
        break;
      }
    }
    if (raw == null) return List.of();

    // Support comma-separated values and repeated flags
    List<String> out = new ArrayList<>();
    for (String v : raw) {
      if (v == null) continue;
      for (String part : v.split(",")) {
        String s = part.trim();
        if (!s.isEmpty()) out.add(s);
      }
    }
    return out;
  }


  private static byte[] runRmlMapperCaptureStdout(Path mappingFile, String serialization) throws Exception {
    PrintStream originalOut = System.out;
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    PrintStream capture = new PrintStream(baos, true, StandardCharsets.UTF_8);
    
    try {
      System.setOut(capture);
      
      // Equivalent to: java -jar rmlmapper.jar -m mapping.ttl -s turtle
      // Note: output defaults to stdout. :contentReference[oaicite:2]{index=2}
      String[] rmlArgs = new String[] {
        "-m", mappingFile.toAbsolutePath().toString(),
        "-s", serialization
      };
      
      // Reflectively call be.ugent.rml.cli.Main.main(rmlArgs)
      Class<?> mainClz = Class.forName(RMLMAPPER_MAIN);
      mainClz.getMethod("main", String[].class).invoke(null, (Object) rmlArgs);
      
    } finally {
      System.setOut(originalOut);
    }
    
    return baos.toByteArray();
  }
  
  private static String contentTypeFor(String serialization) {
    return switch (serialization.toLowerCase(Locale.ROOT)) {
      case "nquads", "n-quads", "nq" -> "application/n-quads";
      case "turtle", "ttl"          -> "text/turtle";
      case "trig"                   -> "application/trig";
      case "trix"                   -> "application/trix+xml";
      case "jsonld", "json-ld"      -> "application/ld+json";
      default                       -> "application/octet-stream";
    };
  }
  
  private static URI extractLocationUri(HttpResponse<?> resp, URI requestUri) {
    // HttpHeaders are case-insensitive, but we'll be defensive.
    Optional<String> loc = resp.headers().firstValue("Location");
    if (loc.isEmpty()) loc = resp.headers().firstValue("location");
    if (loc.isEmpty()) return null;
    
    String raw = loc.get().trim();
    if (raw.isEmpty()) return null;
    
    URI parsed = URI.create(raw);
    
    // Some servers return a relative path; resolve against the POST URL.
    if (!parsed.isAbsolute()) {
      parsed = requestUri.resolve(parsed);
    }
    return parsed;
  }
  
  private static String createUpdateString(
    URI newResourceLocation, 
    URI sourceDatasetUrl, 
    String title,
    String description,
    List<String> keywordArray,
    List<String> ontologyUrls,
    List<String> validationUrls,
    URI feedId
  ) {
    TimeZone tz = TimeZone.getTimeZone("UTC");
    DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'"); // Quoted "Z" to indicate UTC, no timezone offset
    df.setTimeZone(tz);
    
    String resourceLocation = newResourceLocation.toString();
    String creation_id = "urn:deployEMDS:events:create:mapped_" + resourceLocation;
    String datasetUrl = sourceDatasetUrl.toString();
    String publishDateTime = df.format(new Date());
    // String title = "";
    // String description = "";
    String keywords = "";
    String profileId = "urn:deployEMDS:profiles:" + datasetUrl;
    
    for (String keyword: keywordArray) {
      keywords += "\"" + keyword + "\", ";
    }
    
    keywords = keywords.substring(0, keywords.length() - 2);
    
    String updateString = 
"""
#####################################
# dataset entry for %s
#####################################
    
# --- Creation entry ---
<%s>
  a as:Create ;
  as:object <%s> ;
  as:published "%s"^^xsd:dateTime .
    
# --- Member graph ---
<%s> {
    
  # --- Dataset definition ---
  <%s> a dcat:Dataset ;
    dct:title "%s"@en ;
    dct:description "%s"@en ;
    dcat:keyword %s ;
    
    # --- Dataset distribution ---
    dcat:distribution [
      a dcat:Distribution ;
      dcat:accessURL <%s> ;
      dcat:mediaType "text/turtle" ;
      dct:format _:turtle_format ;
    ] .
    
  _:turtle_format a dct:MediaTypeOrExtent ;
    dct:identifier "text/turtle" ;
    rdfs:label "RDF Turtle"@en .
    
  ##########################################
  # Profile entry for <%s>
  ##########################################
    
  <%s>
    a prof:Profile ;
    dct:title "Content profile for %s"@en ;
""";
    updateString = String.format(
      updateString, 
      datasetUrl,
      creation_id,
      datasetUrl,
      publishDateTime,
      creation_id,
      datasetUrl,
      title,
      description,
      keywords,
      newResourceLocation.toString(),
      datasetUrl,
      profileId,
      datasetUrl
    );
    
    for (String ontologyUrl: ontologyUrls) {
      String ontologyString = 
"""
    # --- Used ontologies ---
    prof:hasResource [
      a prof:ResourceDescriptor ;
      prof:hasRole <http://www.w3.org/ns/dx/prof/role/vocabulary> ;
      prof:hasArtifact <%s> ;
      dct:format "text/turtle" ;
      dcat:mediaType "text/turtle" 
    ] ;
""";
      ontologyString = String.format(ontologyString, ontologyUrl);
      updateString += ontologyString;
    }
    
    for (String validationUrl: validationUrls) {
      String validationString = 
"""
    # --- Validation info ---
    prof:hasResource [
      a prof:ResourceDescriptor ;
      prof:hasRole <http://www.w3.org/ns/dx/prof/role/validation> ;
      prof:hasArtifact <%s> ;
      dct:format "text/turtle" ;
      dcat:mediaType "text/turtle" 
    ] .
""";
      validationString = String.format(validationString, validationUrl);
      updateString += validationString;
    }
    
    String profileLinks = 
"""
  # --- Profile link ---
  <%s> dct:conformsTo <%s> .
  <%s> tree:member <%s> .
}
""";
    profileLinks = String.format(profileLinks, datasetUrl, profileId, feedId, creation_id);
    updateString += profileLinks;
    return updateString;
  }
  
  private static void updateFeed(HttpClient client, URI resourceUri, String bearerToken, String updateString) throws IOException, InterruptedException {
    // 1) GET the resource
    HttpRequest.Builder getReq = HttpRequest.newBuilder()
    .uri(resourceUri)
    .timeout(Duration.ofMinutes(2))
    .GET();
    
    if (bearerToken != null && !bearerToken.isBlank()) {
      getReq.header("Authorization", "Bearer " + bearerToken);
    }
    
    HttpResponse<byte[]> getResp = client.send(getReq.build(), HttpResponse.BodyHandlers.ofByteArray());
    System.err.println("GET " + resourceUri + " -> HTTP " + getResp.statusCode());
    
    if (getResp.statusCode() < 200 || getResp.statusCode() >= 300) {
      throw new IOException("GET failed: HTTP " + getResp.statusCode());
    }
    
    byte[] currentBody = getResp.body();
    String etag = getResp.headers().firstValue("ETag").orElse(null);
    
    // 2) Append updateString to existing body (ensure a blank line between)
    String existing = new String(currentBody, StandardCharsets.UTF_8);

    // Normalize line endings a bit (optional but helps)
    existing = existing.replace("\r\n", "\n").replace("\r", "\n");

    String append = (updateString == null) ? "" : updateString;
    append = append.replace("\r\n", "\n").replace("\r", "\n");

    StringBuilder sb = new StringBuilder(existing);

    // Ensure existing ends with exactly one newline, then add another newline
    if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
      sb.append('\n');
    }
    sb.append('\n'); // “leave a newline to be sure”

    sb.append(append);
    if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
      sb.append('\n');
    }

    String resultUpdatedResource = sb.toString();
    System.out.println(resultUpdatedResource);
    byte[] updatedBody = resultUpdatedResource.getBytes(StandardCharsets.UTF_8);

    // Try to preserve server content-type if available; otherwise default.
    String putContentType = getResp.headers().firstValue("Content-Type")
    .map(v -> v.split(";", 2)[0].trim())
    .orElse("application/octet-stream");
    
    // 3) PUT the resource back
    HttpRequest.Builder putReq = HttpRequest.newBuilder()
    .uri(resourceUri)
    .timeout(Duration.ofMinutes(2))
    .header("Content-Type", putContentType)
    .PUT(HttpRequest.BodyPublishers.ofByteArray(updatedBody));
    
    if (bearerToken != null && !bearerToken.isBlank()) {
      putReq.header("Authorization", "Bearer " + bearerToken);
    }
    
    // If server provided an ETag, use conditional PUT to avoid clobbering concurrent updates.
    if (etag != null && !etag.isBlank()) {
      putReq.header("If-Match", etag);
    }
    
    HttpResponse<String> putResp = client.send(putReq.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    System.err.println("PUT " + resourceUri + " -> HTTP " + putResp.statusCode());
    if (putResp.body() != null && !putResp.body().isBlank()) {
      System.err.println(putResp.body());
    }
    
    if (putResp.statusCode() < 200 || putResp.statusCode() >= 300) {
      throw new IOException("PUT failed: HTTP " + putResp.statusCode());
    }
  }
  
}
