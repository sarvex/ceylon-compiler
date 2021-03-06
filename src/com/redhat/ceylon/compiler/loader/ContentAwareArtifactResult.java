package com.redhat.ceylon.compiler.loader;

import java.net.URI;
import java.util.Collection;
import java.util.List;

import com.redhat.ceylon.cmr.api.ArtifactResult;

public interface ContentAwareArtifactResult extends ArtifactResult {
    Collection<String> getPackages();
    Collection<String> getEntries();
    byte[] getContents(String path);
    URI getContentUri(String path);
    List<String> getFileNames(String path);
}