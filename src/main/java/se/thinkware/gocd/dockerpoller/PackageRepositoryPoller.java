package se.thinkware.gocd.dockerpoller;

import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.thoughtworks.go.plugin.api.logging.Logger;

import se.thinkware.gocd.dockerpoller.message.CheckConnectionResultMessage;
import se.thinkware.gocd.dockerpoller.message.PackageMaterialProperties;
import se.thinkware.gocd.dockerpoller.message.PackageRevisionMessage;
import se.thinkware.gocd.dockerpoller.message.ValidationResultMessage;
import se.thinkware.gocd.dockerpoller.message.PackageMaterialProperty;

import java.io.IOException;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static se.thinkware.gocd.dockerpoller.JsonUtil.fromJsonString;

class PackageRepositoryPoller {

    private static Logger LOGGER = Logger.getLoggerFor(PackageRepositoryPoller.class);

    private PackageRepositoryConfigurationProvider configurationProvider;

    private final HttpTransport transport;

    public PackageRepositoryPoller(PackageRepositoryConfigurationProvider configurationProvider) {
        LOGGER.info("Instatiated PackageRepositoryPoller");
        this.configurationProvider = configurationProvider;
        this.transport = new NetHttpTransport();
    }

    // This is used for testing, so that we can mock the HttpTransport
    public PackageRepositoryPoller(
            PackageRepositoryConfigurationProvider configurationProvider,
            HttpTransport transport
    ) {
        this.configurationProvider = configurationProvider;
        this.transport = transport;
    }
    
    private HttpResponse getUrl(GenericUrl url, HttpHeaders basicAuth) throws IOException {
        //HttpHeaders basicAuth = this.getBasicAuthConfig(this.configurationProvider.packageConfiguration());
        HttpRequest request = transport.createRequestFactory().buildGetRequest(url);
        request.setThrowExceptionOnExecuteError(false);

        if (basicAuth != null) {
            request.setHeaders(basicAuth);
        }

        HttpResponse response = request.execute();

        LOGGER.info(String.format("HTTP GET URL: %s %s", url.toString(), response.getStatusCode()));
        if (response.isSuccessStatusCode()) {
            return response;
        } 

        if (response.getStatusCode() == 401) {
            String authenticate = response.getHeaders().getAuthenticate();
            LOGGER.info(String.format("WWW-Authenticate: %s", authenticate));
            if (authenticate != null) {
                Matcher matcher = Pattern
                     .compile("realm=\"([^\"]+)\",service=\"(.*)\",scope=\"(.*)\"", Pattern.CASE_INSENSITIVE)
                     .matcher(authenticate);
                
                matcher.find();                
                String service = matcher.group(2);
                String scope = matcher.group(3);
                String tokenUrl = matcher.group(1) + "?service=" + service + "&scope=" + scope;

                LOGGER.info(String.format("Token URL: %s", tokenUrl));

                HttpRequest tokenRequest = transport
                    .createRequestFactory()
                    .buildGetRequest(new GenericUrl(tokenUrl));

                if (basicAuth != null) {
                    LOGGER.info(String.format("Basic auth: %s", basicAuth.toString()));
                    tokenRequest.setHeaders(basicAuth);
                }
                
                LOGGER.info(String.format("Headers: %s", tokenRequest.getHeaders()));
                LOGGER.info(String.format("URL: %s", tokenRequest.getUrl()));

                String tokenResponse = tokenRequest.execute().parseAsString();

                Map<String, String> tokenMap = new GsonBuilder().create().fromJson(
                    tokenResponse, 
                    new TypeToken<Map<String, String>>(){}.getType()
                );

                LOGGER.info(String.format("Token: %s", tokenMap.get("token")));

                request = transport.createRequestFactory(req -> 
                    req.getHeaders().setAuthorization(
                        "Bearer " + tokenMap.get("token")
                    )
                ).buildGetRequest(url);

                return request.execute();
            }
        }
        
    	throw new HttpResponseException(response);
    }

    private CheckConnectionResultMessage UrlChecker(GenericUrl url, String what) {
        return this.UrlChecker(url, what, null);
    }

    private CheckConnectionResultMessage UrlChecker(GenericUrl url, String what, HttpHeaders basicAuth) {
        LOGGER.info(String.format("Checking URL: %s", url.toString()));
        try {
            HttpResponse response = getUrl(url, basicAuth);
            HttpHeaders headers = response.getHeaders();
            String dockerHeader = "docker-distribution-api-version";
            String linkHeader = "link";
            String message;
            CheckConnectionResultMessage.STATUS status;
            if (headers.containsKey(dockerHeader)) {
                if (headers.get(dockerHeader).toString().startsWith("[registry/2.")) {
                    status = CheckConnectionResultMessage.STATUS.SUCCESS;
                    message = "Docker " + what + " found.";
                    LOGGER.info(message);
                } else {
                    status = CheckConnectionResultMessage.STATUS.FAILURE;
                    message = "Unknown value " + headers.get(dockerHeader).toString() + " for header " + dockerHeader;
                    LOGGER.warn(message);
                }
            } else if (headers.containsKey(linkHeader)) {
                LOGGER.warn(headers.get(linkHeader).toString());
                if (headers.get(linkHeader).toString().startsWith("[</v2/")) {
                    status = CheckConnectionResultMessage.STATUS.SUCCESS;
                    message = "Docker " + what + " found.";
                    LOGGER.info(message);
                } else {
                    status = CheckConnectionResultMessage.STATUS.FAILURE;
                    message = "Unknown value " + headers.get(dockerHeader).toString() + " for header " + dockerHeader;
                    LOGGER.warn(message);
                }
            } else {
                status = CheckConnectionResultMessage.STATUS.FAILURE;
                message = "Missing header: " + dockerHeader + " found only: " + headers.keySet();
                LOGGER.warn(message);
            }
            return new CheckConnectionResultMessage(status, Collections.singletonList(message));
        } catch (IOException ex) {
            String error = "Could not find docker " + what + ". [" + ex.getMessage() + "]";
            LOGGER.warn(error);
            return new CheckConnectionResultMessage(
                    CheckConnectionResultMessage.STATUS.FAILURE,
                    Collections.singletonList(error));
        } catch (Exception ex) {
            LOGGER.warn("Caught unexpected exception");
            LOGGER.warn(ex.toString());
            throw ex;
        }
    }

    List<String> TagFetcher(GenericUrl url) {
        return this.TagFetcher(url, null);
    }

    List<String> TagFetcher(GenericUrl url, HttpHeaders basicAuth) {
        try {
            LOGGER.info(String.format("Fetch tags for %s", url.toString()));
            String tagResponse = getUrl(url, basicAuth).parseAsString();          
            DockerTagsList tagsList = fromJsonString(tagResponse, DockerTagsList.class);
            LOGGER.info(String.format("Got tags: %s", tagsList.getTags().toString()));
            return tagsList.getTags();
        } catch (IOException ex) {
            LOGGER.warn("Got no tags!");
            return Collections.emptyList();
        }
    }

    private HttpHeaders getBasicAuthConfig(PackageMaterialProperties repositoryConfiguration) {
        LOGGER.info(String.format("GetBasicAuth: %s", repositoryConfiguration.getPropertyMap().toString()));
        PackageMaterialProperty registryUsername = repositoryConfiguration.getProperty(Constants.DOCKER_REGISTRY_USERNAME);
        PackageMaterialProperty registryPassword = repositoryConfiguration.getProperty(Constants.DOCKER_REGISTRY_PASSWORD);

        if (registryUsername == null || registryPassword == null) {
            return null;
        }

        LOGGER.info(String.format("Username: %s Password: %s", registryUsername.value(), registryPassword.value()));
        return new HttpHeaders().setBasicAuthentication(registryUsername.value(), registryPassword.value());
    }

    public CheckConnectionResultMessage checkConnectionToRepository(
            PackageMaterialProperties repositoryConfiguration
    ) {
        ValidationResultMessage validationResultMessage =
                configurationProvider.validateRepositoryConfiguration(repositoryConfiguration);
        if (validationResultMessage.failure()) {
            return new CheckConnectionResultMessage(CheckConnectionResultMessage.STATUS.FAILURE, validationResultMessage.getMessages());
        }
        String dockerRegistryUrl = repositoryConfiguration.getProperty(Constants.DOCKER_REGISTRY_URL).value();
        HttpHeaders basicAuth = this.getBasicAuthConfig(repositoryConfiguration);
        return UrlChecker(new GenericUrl(dockerRegistryUrl), "registry", basicAuth);
    }

    public CheckConnectionResultMessage checkConnectionToPackage(
            PackageMaterialProperties packageConfiguration,
            PackageMaterialProperties repositoryConfiguration
    ) {
        String dockerPackageUrl =
                getDockerPackageUrl(packageConfiguration, repositoryConfiguration);
        HttpHeaders basicAuth = this.getBasicAuthConfig(repositoryConfiguration);
        return UrlChecker(new GenericUrl(dockerPackageUrl), "image", basicAuth);
    }

    private String getDockerPackageUrl(
            PackageMaterialProperties packageConfiguration,
            PackageMaterialProperties repositoryConfiguration
    ) {
        return repositoryConfiguration.getProperty(Constants.DOCKER_REGISTRY_URL).value() +
                packageConfiguration.getProperty(Constants.DOCKER_IMAGE).value() +
                "/tags/list";
    }

    static String expandNums(String versionString) {
        Pattern p = Pattern.compile("[0-9]+");
        Matcher m = p.matcher(versionString);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String match = String.format("%06d", Integer.parseInt(m.group()));

            m.appendReplacement(sb, match);
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String biggest(String first, String second) {
        String firstComp = expandNums(first);
        String secondComp = expandNums(second);
        if (firstComp.compareTo(secondComp) > 0) {
            return first;
        } else {
            return second;
        }
    }

    public PackageRevisionMessage getLatestRevision(
            PackageMaterialProperties packageConfiguration,
            PackageMaterialProperties repositoryConfiguration
    ) {
        LOGGER.info("getLatestRevision");
        GenericUrl url = new GenericUrl(getDockerPackageUrl(packageConfiguration, repositoryConfiguration));
        HttpHeaders basicAuth = this.getBasicAuthConfig(repositoryConfiguration);
        List<String> tags = TagFetcher(url, basicAuth);
        String filter = packageConfiguration.getProperty(Constants.DOCKER_TAG_FILTER).value();
        if (filter.equals("")) {
            filter = ".*";
        }
        Pattern pattern = Pattern.compile(filter);

        List<Object> matching = tags.stream().filter(pattern.asPredicate()).collect(Collectors.toList());

        if (matching.isEmpty()) {
            LOGGER.info("Found no matching revision.");
            return new PackageRevisionMessage();
        }

        LOGGER.info("Looking for biggest");
        String latest = "";
        for (Object tag: matching) {
            latest = biggest(latest, tag.toString());
        }

        LOGGER.info(String.format("Latest revision is: %s", latest));
        return new PackageRevisionMessage(latest, new Date(), "docker", null,null);
    }

    public PackageRevisionMessage getLatestRevisionSince(
            PackageMaterialProperties packageConfiguration,
            PackageMaterialProperties repositoryConfiguration,
            PackageRevisionMessage previous
    ) {
        LOGGER.info(String.format("getLatestRevisionSince %s", previous.getRevision()));
        PackageRevisionMessage latest = getLatestRevision(packageConfiguration, repositoryConfiguration);
        if (biggest(previous.getRevision(), latest.getRevision()).equals(latest.getRevision())) {
            LOGGER.info(String.format("Latest revision is: %s", latest));
            return latest;
        } else {
            LOGGER.info("Found no matching revision.");
            return new PackageRevisionMessage();
        }
    }

}
