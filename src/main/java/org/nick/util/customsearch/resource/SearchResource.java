package org.nick.util.customsearch.resource;

import lombok.RequiredArgsConstructor;
import org.nick.util.customsearch.model.Query;
import org.nick.util.customsearch.model.Store;
import org.nick.util.customsearch.service.SearchService;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
@RequiredArgsConstructor
public class SearchResource {
    private final SearchService searchService;

    @PostMapping("/store")
    public Set<Store> findStores(@RequestBody List<Query> queries) {
        Assert.isTrue(queries.size() > 1, "Queries length must be greater than 1");
        return searchService.findInStores(queries);
    }
}
