package org.nick.util.customsearch.model;

import lombok.RequiredArgsConstructor;
import lombok.Value;

/**
 * Created by VNikolaenko on 29.06.2015.
 */
@Value
@RequiredArgsConstructor
public class Store implements Searchable {
    private final String link;
}
