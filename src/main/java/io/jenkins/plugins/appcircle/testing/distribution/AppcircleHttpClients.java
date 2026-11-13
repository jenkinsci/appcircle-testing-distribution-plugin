package io.jenkins.plugins.appcircle.testing.distribution;

import org.apache.http.HttpHeaders;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpCoreContext;

final class AppcircleHttpClients {
    private AppcircleHttpClients() {}

    static CloseableHttpClient createDefault() {
        return builder().build();
    }

    static HttpClientBuilder builder() {
        return HttpClients.custom().addInterceptorLast(new DropTokenOnCrossHostRedirect());
    }

    static boolean keepsToken(HttpHost first, HttpHost next) {
        if (!first.getHostName().equalsIgnoreCase(next.getHostName())) {
            return false;
        }
        if (isHttps(first) && !isHttps(next)) {
            return false;
        }
        return port(first) == port(next) || (isDefaultPort(first) && isDefaultPort(next));
    }

    private static boolean isHttps(HttpHost host) {
        return "https".equalsIgnoreCase(host.getSchemeName());
    }

    private static int port(HttpHost host) {
        if (host.getPort() != -1) {
            return host.getPort();
        }
        return isHttps(host) ? 443 : 80;
    }

    private static boolean isDefaultPort(HttpHost host) {
        return port(host) == (isHttps(host) ? 443 : 80);
    }

    static final class DropTokenOnCrossHostRedirect implements HttpRequestInterceptor {
        private static final String FIRST_TARGET = "appcircle.firstTarget";

        @Override
        public void process(HttpRequest request, HttpContext context) {
            Object target = context.getAttribute(HttpCoreContext.HTTP_TARGET_HOST);
            if (!(target instanceof HttpHost)) {
                return;
            }
            Object first = context.getAttribute(FIRST_TARGET);
            if (!(first instanceof HttpHost)) {
                context.setAttribute(FIRST_TARGET, target);
                return;
            }
            if (!keepsToken((HttpHost) first, (HttpHost) target)) {
                request.removeHeaders(HttpHeaders.AUTHORIZATION);
            }
        }
    }
}
