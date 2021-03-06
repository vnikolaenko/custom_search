package org.nick.util.customsearch.config;

import jdk.incubator.http.HttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.security.NoSuchAlgorithmException;

@Configuration
public class UtilConfig {
    @Bean
    public HttpClient httpClient(@Value("${proxy.host:localhost}") String proxyHost,
                                 @Value("${proxy.host:5566}") int proxyPort) throws NoSuchAlgorithmException {
        return HttpClient.newBuilder()
                .proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .sslContext(SSLContext.getDefault())
                .sslParameters(new SSLParameters())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Bean
    public RestTemplate restTemplate(@Value("${proxy.host:localhost}") String proxyHost,
                                     @Value("${proxy.host:5566}") int proxyPort) {
        final SimpleClientHttpRequestFactory httpRequestFactory = new SimpleClientHttpRequestFactory();
        httpRequestFactory.setProxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort)));
        return new RestTemplate(httpRequestFactory);
    }
}
