package org.nick.util.customsearch.service;

import io.webfolder.ui4j.api.browser.BrowserFactory;
import io.webfolder.ui4j.api.browser.Page;
import io.webfolder.ui4j.api.browser.PageConfiguration;
import io.webfolder.ui4j.api.dom.Document;
import io.webfolder.ui4j.api.interceptor.Interceptor;
import io.webfolder.ui4j.api.interceptor.Request;
import io.webfolder.ui4j.api.interceptor.Response;
import jdk.incubator.http.HttpClient;
import jdk.incubator.http.HttpResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nick.util.customsearch.model.Query;
import org.nick.util.customsearch.model.Store;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;
import static jdk.incubator.http.HttpRequest.newBuilder;

/**
 * Created by VNikolaenko on 08.07.2015.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {
    private static final String SELECTOR_LIST_ITEM_STORE = ".list-item .store";

    private static final ExecutorService POOL_WEBKIT = Executors.newCachedThreadPool();

    private static final String AEP_USUC_F = "aep_usuc_f";
    private static final String SITE_GLO_REGION_RU_B_LOCALE_EN_US_C_TP_RUB = "site=glo&region=RU&b_locale=en_US&c_tp=RUB";
    private static final List<HttpCookie> SEARCH_COOKIES = Collections.singletonList(new HttpCookie(AEP_USUC_F, SITE_GLO_REGION_RU_B_LOCALE_EN_US_C_TP_RUB));
    private static final String PATH_SEARCH_IN_STORE = "/search?origin=y&SearchText=";

    private static final String PARAM_PAGE = "&page=";
    private static final String PARAM_MIN_PRICE = "&minPrice=";
    private static final String PARAM_MAX_PRICE = "&maxPrice=";

    @Value("${app.proxy.host}")
    private String proxyHost;

    @Value("${app.proxy.port}")
    private int proxyPort;

    @Value("${app.path}")
    private String mainPath;

    private final HttpClient httpClient;

    private Optional<Document> getDocument(final URL url) {
        final Interceptor interceptor = new Interceptor() {
            @Override
            public void beforeLoad(Request request) {
                try {
                    final URLConnection urlConnection = url.openConnection(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort)));
                    urlConnection.setConnectTimeout(5000);
                    urlConnection.setReadTimeout(5000);
                    request.setUrlConnection(urlConnection);
                } catch (IOException e) {
                    throw new RuntimeException("Fatal error", e);
                }
                request.setCookies(SEARCH_COOKIES);
            }

            @Override
            public void afterLoad(Response response) {
            }
        };
        final PageConfiguration pageConfiguration = new PageConfiguration(interceptor);
        pageConfiguration.setInterceptAllRequests(true);
        final Page page = BrowserFactory.getWebKit().navigate(url.toString(), pageConfiguration);
        page.executeScript("document.documentElement.innerHTML");

        return Optional.ofNullable(page.getDocument());
    }

    private Set<Store> getSearchResults(final URL url) {
        return getDocument(url).map(document -> document.queryAll(SELECTOR_LIST_ITEM_STORE).stream()
                .map(storeNode -> new Store(storeNode.getAttribute("href").orElse(""), storeNode.getText().orElse("")))
                .collect(Collectors.toSet()))
                .orElseGet(() -> {
                    log.warn("[DOCUMENT ERROR] {}", url);
                    return Collections.emptySet();
                });
    }

    private boolean filterStores(Store store, Collection<Query> queries) {
        return queries.stream().map(query ->
                httpClient.sendAsync(
                        newBuilder(createInstoreSearchURL(store.getLink(), query))
                                .timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandler.asString())
                        .thenApplyAsync(response -> { // handle response
                            if (response.statusCode() == 200) {
                                return storeHasItem(response.body());
                            } else { //log non 200
                                log.warn("Not 200 status code {} [STORE] {} [QUERY] {}", response.statusCode(), store, query);
                                return false;
                            }
                        })
                        .exceptionally(exception -> { // handle exceptions
                            log.warn("[EXCEPTION] {} [QUERY] {}", exception.getMessage(), query);
                            log.debug("[EXCEPTION] " + exception.getMessage() + " [QUERY] " + query, exception);
                            return false;
                        })).collect(Collectors.toList())//for async
                .stream().map(CompletableFuture::join).reduce((a, b) -> a & b).orElse(false); // merge responses
    }

    private boolean storeHasItem(String responseBody) {
        return responseBody.contains("items-list util-clearfix");
    }

    private URI createInstoreSearchURL(String storeLink, Query criteria) {
        //todo add all criteria parts
        final String preparedStoreLink = storeLink.startsWith("//") ? "http:" + storeLink : storeLink;

        try {
            return new URL(preparedStoreLink + PATH_SEARCH_IN_STORE + criteria.getSearchText()).toURI();
        } catch (MalformedURLException | URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private Stream<Store> allStoresByQuery(final Query query) {
        return IntStream.range(1, query.getPages4Processing() + 1).parallel().boxed()
                .map(page -> CompletableFuture
                        .supplyAsync(() -> url(query, page).map(this::getSearchResults).orElse(Collections.emptySet()), POOL_WEBKIT)
                        .thenApplyAsync(Optional::of)
                        .exceptionally(exception -> {
                            log.warn("[EXCEPTION] {} [QUERY] {}", exception.getMessage(), query);
                            log.debug("[EXCEPTION] " + exception.getMessage() + " [QUERY] " + query, exception);
                            return Optional.empty();
                        })
                ).collect(Collectors.toList()) //for async
                .stream().map(CompletableFuture::join) //start http
                .flatMap(Optional::stream).flatMap(Collection::stream);
    }

    public Set<Store> findInStores(final String... queries) {
        return findInStores(Stream.of(queries).map(Query::of).collect(Collectors.toList()));
    }

    public Set<Store> findInStores(final Collection<Query> queries) {
        final List<Query> subQueryList = queries.stream().skip(1).collect(Collectors.toList());
        final Optional<Query> firstQuery = queries.stream().findFirst();

        if (firstQuery.isPresent()) {
            return allStoresByQuery(firstQuery.get()).filter(store -> filterStores(store, subQueryList)).collect(Collectors.toSet());
        } else {
            return Collections.emptySet();
        }
    }

    public Optional<URL> url(Query query, Integer page) {
        final String spec = mainPath + query.getSearchText()
                + (ofNullable(page).map(x -> PARAM_PAGE + page).orElse(""))
                + (ofNullable(query.getMinPrice()).map(x -> PARAM_MIN_PRICE + x).orElse(""))
                + (ofNullable(query.getMaxPrice()).map(x -> PARAM_MAX_PRICE + x).orElse(""));
        try {
            return Optional.of(new URL(spec));
        } catch (MalformedURLException e) {
            log.warn("Url [{}] parse error", spec, e);
        }

        return Optional.empty();
    }

    @PreDestroy
    private void preDestroy() {
        POOL_WEBKIT.shutdown();
    }
}