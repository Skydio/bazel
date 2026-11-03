// Copyright 2026 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.query2.query.hash;

import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.bazel.bzlmod.LocalPathRepoSpecs;
import com.google.devtools.build.lib.bazel.bzlmod.RepoRuleId;
import com.google.devtools.build.lib.bazel.repository.RepoDefinition;
import com.google.devtools.build.lib.bazel.repository.RepoDefinitionValue;
import com.google.devtools.build.lib.bazel.repository.cache.LocalRepoContentsCache;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.InMemoryRepoMarkerFiles;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.skyframe.AbstractSkyKey;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.IOException;
import java.util.Arrays;
import javax.annotation.Nullable;

/**
 * Computes a single digest of an external repository, for {@code --generate_hashes}.
 *
 * <p>An external repo is hashed by its marker file, which records the predeclared inputs of its
 * repo rule (Bazel version, flags, repo rule definition and attributes) and the values of every
 * input it read: watched files, directory listings and environment variables. Its files are not
 * read. The marker is the same whether the repo was fetched into the external directory, is served
 * from the local repo contents cache (as the entry's recorded inputs) or from the remote one (held
 * in memory), so all three hash the same, and two machines that fetch a repo from the same inputs
 * hash it the same even when the files they produce differ.
 *
 * <p>That holds for {@code local} and {@code configure} repos and for repos whose fetch is not
 * reproducible too. What their marker does not record is not part of the hash: a host file a repo
 * rule symlinks without watching it, or the bytes of a download it does not pin. A repo rule that
 * wants such an input to change the hash must watch it, or read a checked-in file that pins it.
 *
 * <p>For the main repo, overridden repos and {@code local_repository} or {@code
 * new_local_repository} repos whose path is in the workspace, {@link Value#usesFileDigests} is true
 * and source files are hashed individually by {@link SourceFileDigestFunction}: those are files
 * people edit, and their marker does not record their contents. So is any other repo whose marker
 * cannot be found.
 */
public final class RepoContentDigestFunction implements SkyFunction {
  // Not hermetic: the marker is read directly instead of through Skyframe.
  public static final SkyFunctionName NAME =
      SkyFunctionName.createNonHermetic("QUERY_REPO_CONTENT_DIGEST");

  private static final RepoRuleId NEW_LOCAL_REPOSITORY =
      new RepoRuleId(LocalPathRepoSpecs.LOCAL_REPOSITORY.bzlFileLabel(), "new_local_repository");

  private final BlazeDirectories directories;

  public RepoContentDigestFunction(BlazeDirectories directories) {
    this.directories = directories;
  }

  public static Key key(RepositoryName repo) {
    return Key.create(repo);
  }

  /** Key for {@link RepoContentDigestFunction}. */
  public static final class Key extends AbstractSkyKey<RepositoryName> {
    private static final SkyKeyInterner<Key> interner = SkyKey.newInterner();

    private Key(RepositoryName arg) {
      super(arg);
    }

    private static Key create(RepositoryName arg) {
      return interner.intern(new Key(arg));
    }

    @Override
    public SkyFunctionName functionName() {
      return NAME;
    }

    @Override
    public SkyKeyInterner<Key> getSkyKeyInterner() {
      return interner;
    }
  }

  /** The digest of a repo, or null if its files must be hashed individually. */
  public static final class Value implements SkyValue {
    static final Value USE_FILE_DIGESTS = new Value(null);

    @Nullable private final byte[] digest;

    private Value(@Nullable byte[] digest) {
      this.digest = digest;
    }

    public boolean usesFileDigests() {
      return digest == null;
    }

    /** The digest of the repo, or null if {@link #usesFileDigests} is true. */
    @Nullable
    public byte[] getDigest() {
      return digest;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Value other && Arrays.equals(digest, other.digest);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(digest);
    }
  }

  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws RepoContentDigestFunctionException, InterruptedException {
    RepositoryName repo = (RepositoryName) skyKey.argument();
    if (repo.isMain()) {
      return Value.USE_FILE_DIGESTS;
    }
    var definition = (RepoDefinitionValue) env.getValue(RepoDefinitionValue.key(repo));
    if (definition == null) {
      return null;
    }
    if (!(definition instanceof RepoDefinitionValue.Found(RepoDefinition repoDefinition))
        || isInWorkspace(repoDefinition)) {
      return Value.USE_FILE_DIGESTS;
    }
    var directory = (RepositoryDirectoryValue) env.getValue(RepositoryDirectoryValue.key(repo));
    if (directory == null) {
      return null;
    }
    if (!(directory instanceof RepositoryDirectoryValue.Success success)) {
      return Value.USE_FILE_DIGESTS;
    }
    Path externalRoot =
        directories.getOutputBase().getRelative(LabelConstants.EXTERNAL_REPOSITORY_LOCATION);
    String marker;
    try {
      marker = marker(repo, success.root().asPath(), externalRoot);
    } catch (IOException e) {
      throw new RepoContentDigestFunctionException(e);
    }
    if (marker == null) {
      return Value.USE_FILE_DIGESTS;
    }
    return new Value(new Fingerprint().addString("<marker>").addString(marker).digestAndReset());
  }

  /**
   * Returns whether {@code definition} is a {@code local_repository} or {@code
   * new_local_repository} whose path is in the workspace, so that its files are checked in.
   */
  private boolean isInWorkspace(RepoDefinition definition) {
    RepoRuleId id = definition.repoRule().id();
    if (!id.equals(LocalPathRepoSpecs.LOCAL_REPOSITORY) && !id.equals(NEW_LOCAL_REPOSITORY)) {
      return false;
    }
    if (!(definition.attrValues().attributes().get("path") instanceof String path)) {
      return false;
    }
    // Resolved the way local.bzl resolves it: a relative path is relative to the workspace.
    Path workspace = directories.getWorkspace();
    Path dir = workspace.getRelative(path);
    return dir.startsWith(workspace) && !dir.startsWith(directories.getOutputBase());
  }

  /** Returns the contents of the marker file of {@code repo}, or null if it has none. */
  @Nullable
  private static String marker(RepositoryName repo, Path root, Path externalRoot)
      throws IOException {
    if (root.getFileSystem() instanceof InMemoryRepoMarkerFiles inMemory) {
      String marker = inMemory.getInMemoryMarkerFileContents(repo.getName());
      if (marker != null) {
        // Served from the remote repo contents cache.
        return marker;
      }
    }
    FileStatus status = root.statIfFound(Symlinks.NOFOLLOW);
    if (status == null) {
      return null;
    }
    if (status.isSymbolicLink()) {
      // An entry of the local repo contents cache, whose marker Bazel moves next to it, or a repo
      // rule that symlinked the repo directory itself, whose marker is in the external directory.
      Path entry = root.resolveSymbolicLinks();
      Path recordedInputs =
          entry
              .getParentDirectory()
              .getChild(entry.getBaseName() + LocalRepoContentsCache.RECORDED_INPUTS_SUFFIX);
      if (recordedInputs.isFile()) {
        return readLatin1(recordedInputs);
      }
    }
    Path marker = externalRoot.getChild(repo.getMarkerFileName());
    return marker.isFile() ? readLatin1(marker) : null;
  }

  private static String readLatin1(Path path) throws IOException {
    return new String(FileSystemUtils.readContentAsLatin1(path));
  }

  private static final class RepoContentDigestFunctionException extends SkyFunctionException {
    RepoContentDigestFunctionException(IOException e) {
      super(e, Transience.TRANSIENT);
    }
  }
}
