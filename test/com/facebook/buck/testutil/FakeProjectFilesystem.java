/*
 * Copyright 2013-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.testutil;

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.timing.FakeClock;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Splitter;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigInteger;
import java.nio.file.CopyOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotLinkException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;

import javax.annotation.Nullable;

import okio.Buffer;
import okio.BufferedSource;

// TODO(natthu): Implement methods that throw UnsupportedOperationException.
public class FakeProjectFilesystem extends ProjectFilesystem {

  private static final Random RANDOM = new Random();

  private static final BasicFileAttributes DEFAULT_FILE_ATTRIBUTES =
      new BasicFileAttributes() {
        @Override
        @Nullable
        public FileTime lastModifiedTime() {
          return null;
        }

        @Override
        @Nullable
        public FileTime lastAccessTime() {
          return null;
        }

        @Override
        @Nullable
        public FileTime creationTime() {
          return null;
        }

        @Override
        public boolean isRegularFile() {
          return true;
        }

        @Override
        public boolean isDirectory() {
          return false;
        }

        @Override
        public boolean isSymbolicLink() {
          return false;
        }

        @Override
        public boolean isOther() {
          return false;
        }

        @Override
        public long size() {
          return 0;
        }

        @Override
        @Nullable
        public Object fileKey() {
          return null;
        }
      };

  private static final BasicFileAttributes DEFAULT_DIR_ATTRIBUTES =
      new BasicFileAttributes() {
        @Override
        @Nullable
        public FileTime lastModifiedTime() {
          return null;
        }

        @Override
        @Nullable
        public FileTime lastAccessTime() {
          return null;
        }

        @Override
        @Nullable
        public FileTime creationTime() {
          return null;
        }

        @Override
        public boolean isRegularFile() {
          return false;
        }

        @Override
        public boolean isDirectory() {
          return true;
        }

        @Override
        public boolean isSymbolicLink() {
          return false;
        }

        @Override
        public boolean isOther() {
          return false;
        }

        @Override
        public long size() {
          return 0;
        }

        @Override
        @Nullable
        public Object fileKey() {
          return null;
        }
      };

  private final Map<Path, byte[]> fileContents;
  private final Map<Path, ImmutableSet<FileAttribute<?>>> fileAttributes;
  private final Map<Path, FileTime> fileLastModifiedTimes;
  private final Map<Path, Path> symLinks;
  private final Set<Path> directories;
  private final Clock clock;

  public FakeProjectFilesystem() {
    this(new FakeClock(0), Paths.get(".").toFile(), ImmutableSet.<Path>of());
  }

  // We accept a File here since that's what's returned by TemporaryFolder.
  public FakeProjectFilesystem(File root) {
    this(new FakeClock(0), root, ImmutableSet.<Path>of());
  }

  public FakeProjectFilesystem(Clock clock) {
    this(clock, Paths.get(".").toFile(), ImmutableSet.<Path>of());
  }

  public FakeProjectFilesystem(Set<Path> files) {
    this(new FakeClock(0), Paths.get(".").toFile(), files);
  }

  public FakeProjectFilesystem(Clock clock, File root, Set<Path> files) {
    super(root.toPath());
    // We use LinkedHashMap to preserve insertion order, so the
    // behavior of this test is consistent across versions. (It also lets
    // us write tests which explicitly test iterating over entries in
    // different orders.)
    fileContents = new LinkedHashMap<>();
    fileLastModifiedTimes = new LinkedHashMap<>();
    FileTime modifiedTime = FileTime.fromMillis(clock.currentTimeMillis());
    for (Path file : files) {
      fileContents.put(file, new byte[0]);
      fileLastModifiedTimes.put(file, modifiedTime);
    }
    fileAttributes = new LinkedHashMap<>();
    symLinks = new LinkedHashMap<>();
    directories = new LinkedHashSet<>();
    directories.add(Paths.get(""));
    for (Path file : files) {
      Path dir = file.getParent();
      while (dir != null) {
        directories.add(dir);
        dir = dir.getParent();
      }
    }
    this.clock = Preconditions.checkNotNull(clock);

    // Generally, tests don't care whether files exist.
    ignoreValidityOfPaths = true;
  }

  public FakeProjectFilesystem setIgnoreValidityOfPaths(boolean shouldIgnore) {
    this.ignoreValidityOfPaths = shouldIgnore;
    return this;
  }

  private byte[] getFileBytes(Path path) {
    return Preconditions.checkNotNull(fileContents.get(MorePaths.normalize(path)));
  }

  private void rmFile(Path path) {
    fileContents.remove(MorePaths.normalize(path));
    fileAttributes.remove(MorePaths.normalize(path));
    fileLastModifiedTimes.remove(MorePaths.normalize(path));
  }

  public ImmutableSet<FileAttribute<?>> getFileAttributesAtPath(Path path) {
    return Preconditions.checkNotNull(fileAttributes.get(path));
  }

  @Override
  public <A extends BasicFileAttributes> A readAttributes(
      Path pathRelativeToProjectRoot,
      Class<A> type,
      LinkOption... options) throws IOException {
    // Converting FileAttribute to BasicFileAttributes sub-interfaces is
    // really annoying. Let's just not do it.
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean exists(Path path) {
    return isFile(path) || isDirectory(path);
  }

  @Override
  public long getFileSize(Path path) throws IOException {
    if (!exists(path)) {
      throw new FileNotFoundException(path.toString());
    }
    return getFileBytes(path).length;
  }

  @Override
  public void deleteFileAtPath(Path path) throws IOException {
    if (exists(path)) {
      rmFile(path);
    } else {
      throw new NoSuchFileException(path.toString());
    }
  }

  @Override
  public boolean deleteFileAtPathIfExists(Path path) throws IOException {
    if (exists(path)) {
      rmFile(path);
      return true;
    } else {
      return false;
    }
  }

  @Override
  public Properties readPropertiesFile(Path pathToPropertiesFile) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isFile(Path path) {
    return fileContents.containsKey(MorePaths.normalize(path));
  }

  @Override
  public boolean isHidden(Path path) throws IOException {
    return isFile(path) && path.getFileName().toString().startsWith(".");
  }

  @Override
  public boolean isDirectory(Path path, LinkOption... linkOptions) {
    return directories.contains(MorePaths.normalize(path));
  }

  @Override
  public boolean isExecutable(Path child) {
    return false;
  }

  /**
   * Does not support symlinks.
   */
  @Override
  public ImmutableCollection<Path> getDirectoryContents(final Path pathRelativeToProjectRoot)
      throws IOException {
    Preconditions.checkState(isDirectory(pathRelativeToProjectRoot));
    return FluentIterable
        .from(fileContents.keySet())
        .append(directories)
        .filter(
            new Predicate<Path>() {
              @Override
              public boolean apply(Path input) {
                if (input.equals(Paths.get(""))) {
                  return false;
                }
                return MorePaths.getParentOrEmpty(input).equals(pathRelativeToProjectRoot);
              }
            })
        .toList();
  }

  @Override
  public ImmutableSortedSet<Path> getSortedMatchingDirectoryContents(
      final Path pathRelativeToProjectRoot,
      String globPattern)
      throws IOException {
    Preconditions.checkState(isDirectory(pathRelativeToProjectRoot));
    final PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher("glob:" + globPattern);
    return FluentIterable.from(fileContents.keySet()).filter(
        new Predicate<Path>() {
          @Override
          public boolean apply(Path input) {
            return input.getParent().equals(pathRelativeToProjectRoot) &&
                pathMatcher.matches(input.getFileName());
          }
        })
        .toSortedSet(
            Ordering
                .natural()
                .onResultOf(
                    new Function<Path, FileTime>() {
                      @Override
                      public FileTime apply(Path path) {
                        try {
                          return getLastModifiedTimeFetcher().getLastModifiedTime(path);
                        } catch (IOException e) {
                          throw new RuntimeException(e);
                        }
                      }
                    })
                .compound(Ordering.natural())
                .reverse());
  }

  @Override
  public void walkFileTree(Path root, FileVisitor<Path> fileVisitor) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public long getLastModifiedTime(Path path) throws IOException {
    Path normalizedPath = MorePaths.normalize(path);
    if (!exists(normalizedPath)) {
      throw new NoSuchFileException(path.toString());
    }
    return Preconditions.checkNotNull(fileLastModifiedTimes.get(normalizedPath)).toMillis();
  }

  @Override
  public Path setLastModifiedTime(Path path, FileTime time) throws IOException {
    Path normalizedPath = MorePaths.normalize(path);
    if (!exists(normalizedPath)) {
      throw new NoSuchFileException(path.toString());
    }
    fileLastModifiedTimes.put(normalizedPath, time);
    return normalizedPath;
  }

  @Override
  public void deleteRecursively(Path path) throws IOException {
    if (!exists(path)) {
      throw new NoSuchFileException(path.toString());
    }

    deleteRecursivelyIfExists(path);
  }

  @Override
  public void deleteRecursivelyIfExists(Path path) throws IOException {
    Path normalizedPath = MorePaths.normalize(path);
    for (Iterator<Path> iterator = fileContents.keySet().iterator(); iterator.hasNext();) {
      Path subPath = iterator.next();
      if (subPath.startsWith(normalizedPath)) {
        fileAttributes.remove(MorePaths.normalize(subPath));
        fileLastModifiedTimes.remove(MorePaths.normalize(subPath));
        iterator.remove();
      }
    }
    for (Iterator<Path> iterator = symLinks.keySet().iterator(); iterator.hasNext();) {
      Path subPath = iterator.next();
      if (subPath.startsWith(normalizedPath)) {
        iterator.remove();
      }
    }
    fileLastModifiedTimes.remove(path);
    directories.remove(path);
  }

  @Override
  public void mkdirs(Path path) throws IOException {
    for (int i = 0; i < path.getNameCount(); i++) {
      Path subpath = path.subpath(0, i + 1);
      directories.add(subpath);
      fileLastModifiedTimes.put(subpath, FileTime.fromMillis(clock.currentTimeMillis()));
    }
  }

  @Override
  public Path createNewFile(Path path) throws IOException {
    writeBytesToPath(new byte[0], path);
    return path;
  }

  @Override
  public void writeLinesToPath(
      Iterable<String> lines,
      Path path,
      FileAttribute<?>... attrs) throws IOException {
    StringBuilder builder = new StringBuilder();
    if (!Iterables.isEmpty(lines)) {
      Joiner.on('\n').appendTo(builder, lines);
      builder.append('\n');
    }
    writeContentsToPath(builder.toString(), path, attrs);
  }

  @Override
  public void writeContentsToPath(
      String contents,
      Path path,
      FileAttribute<?>... attrs) throws IOException {
    writeBytesToPath(contents.getBytes(Charsets.UTF_8), path, attrs);
  }

  @Override
  public void writeBytesToPath(
      byte[] bytes,
      Path path,
      FileAttribute<?>... attrs) throws IOException {
    Path normalizedPath = MorePaths.normalize(path);
    fileContents.put(normalizedPath, Preconditions.checkNotNull(bytes));
    fileAttributes.put(normalizedPath, ImmutableSet.copyOf(attrs));

    Path directory = normalizedPath.getParent();
    while (directory != null) {
      directories.add(directory);
      directory = directory.getParent();
    }
    fileLastModifiedTimes.put(normalizedPath, FileTime.fromMillis(clock.currentTimeMillis()));
  }

  @Override
  public OutputStream newFileOutputStream(
      final Path pathRelativeToProjectRoot,
      final FileAttribute<?>... attrs) throws IOException {
    return new ByteArrayOutputStream() {
      @Override
      public void close() throws IOException {
        super.close();
        writeToMap();
      }

      @Override
      public void flush() throws IOException {
        super.flush();
        writeToMap();
      }

      private void writeToMap() throws IOException {
        writeBytesToPath(toByteArray(), pathRelativeToProjectRoot, attrs);
      }
    };
  }

  /**
   * Does not support symlinks.
   */
  @Override
  public InputStream newFileInputStream(Path pathRelativeToProjectRoot)
    throws IOException {
    byte[] contents = fileContents.get(normalizePathToProjectRoot(pathRelativeToProjectRoot));
    return new ByteArrayInputStream(contents);
  }


  @Override
  public BufferedSource newSource(Path pathRelativeToProjectRoot) throws IOException {
    Buffer buffer = new Buffer();
    buffer.write(fileContents.get(normalizePathToProjectRoot(pathRelativeToProjectRoot)));
    return buffer;
  }

  private Path normalizePathToProjectRoot(Path pathRelativeToProjectRoot)
    throws NoSuchFileException {
    if (!exists(pathRelativeToProjectRoot)) {
      throw new NoSuchFileException(pathRelativeToProjectRoot.toString());
    }
    return MorePaths.normalize(pathRelativeToProjectRoot);
  }

  @Override
  public void copyToPath(final InputStream inputStream, Path path, CopyOption... options)
      throws IOException {
    writeBytesToPath(ByteStreams.toByteArray(inputStream), path);
  }

  /**
   * Does not support symlinks.
   */
  @Override
  public Optional<String> readFileIfItExists(Path path) {
    if (!exists(path)) {
      return Optional.absent();
    }
    return Optional.of(new String(getFileBytes(path), Charsets.UTF_8));
  }

  /**
   * Does not support symlinks.
   */
  @Override
  public Optional<Reader> getReaderIfFileExists(Path path) {
    Optional<String> content = readFileIfItExists(path);
    if (!content.isPresent()) {
      return Optional.absent();
    }
    return Optional.of((Reader) new StringReader(content.get()));
  }

  /**
   * Does not support symlinks.
   */
  @Override
  public Optional<String> readFirstLine(Path path) {
    List<String> lines;
    try {
      lines = readLines(path);
    } catch (IOException e) {
      return Optional.absent();
    }

    return Optional.fromNullable(Iterables.get(lines, 0, null));
  }

  /**
   * Does not support symlinks.
   */
  @Override
  public List<String> readLines(Path path) throws IOException {
    Optional<String> contents = readFileIfItExists(path);
    if (!contents.isPresent() || contents.get().isEmpty()) {
      return ImmutableList.of();
    }
    String content = contents.get();
    content = content.endsWith("\n") ? content.substring(0, content.length() - 1) : content;
    return Splitter.on('\n').splitToList(content);
  }

  /**
   * Does not support symlinks.
   */
  @Override
  public String computeSha1(Path path) throws IOException {
    if (!exists(path)) {
      throw new FileNotFoundException(path.toString());
    }
    return Hashing.sha1().hashBytes(getFileBytes(path)).toString();
  }

  /**
   * TODO(natthu): (1) Also traverse the directories. (2) Do not ignore return value of
   * {@code fileVisitor}.
   */
  @Override
  public void walkRelativeFileTree(
      Path path,
      EnumSet<FileVisitOption> visitOptions,
      FileVisitor<Path> fileVisitor) throws IOException {

    if (!isDirectory(path)) {
      fileVisitor.visitFile(path, DEFAULT_FILE_ATTRIBUTES);
      return;
    }

    ImmutableCollection<Path> ents = getDirectoryContents(path);
    for (Path ent : ents) {
      if (!isDirectory(ent)) {
        FileVisitResult result = fileVisitor.visitFile(ent, DEFAULT_FILE_ATTRIBUTES);
        if (result == FileVisitResult.SKIP_SIBLINGS) {
          return;
        }
      } else {
        FileVisitResult result = fileVisitor.preVisitDirectory(ent, DEFAULT_DIR_ATTRIBUTES);
        if (result == FileVisitResult.SKIP_SIBLINGS) {
          return;
        }
        if (result != FileVisitResult.SKIP_SUBTREE) {
          walkRelativeFileTree(ent, fileVisitor);
          fileVisitor.postVisitDirectory(ent, null);
        }
      }
    }
  }

  @Override
  public void copyFolder(Path source, Path target) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void copyFile(Path source, Path target) throws IOException {
    writeContentsToPath(readFileIfItExists(source).get(), target);
  }

  @Override
  public void createSymLink(Path symLink, Path realFile, boolean force) throws IOException {
    if (!force) {
      if (fileContents.containsKey(symLink) || directories.contains(symLink)) {
        throw new FileAlreadyExistsException(symLink.toString());
      }
    } else {
      rmFile(symLink);
      deleteRecursivelyIfExists(symLink);
    }
    symLinks.put(symLink, realFile);
  }

  @Override
  public boolean isSymLink(Path path) throws IOException {
    return symLinks.containsKey(path);
  }

  @Override
  public Path readSymLink(Path path) throws IOException {
    Path target = symLinks.get(path);
    if (target == null) {
      throw new NotLinkException(path.toString());
    }
    return target;
  }

  @Override
  public void touch(Path fileToTouch) throws IOException {
    if (exists(fileToTouch)) {
      setLastModifiedTime(fileToTouch, FileTime.fromMillis(clock.currentTimeMillis()));
    } else {
      createNewFile(fileToTouch);
    }
  }

  @Override
  public Path createTempFile(
      Path directory,
      String prefix,
      String suffix,
      FileAttribute<?>... attrs) throws IOException {
    Path path;
    do {
      String str = new BigInteger(130, RANDOM).toString(32);
      path = directory.resolve(prefix + str + suffix);
    } while (exists(path));
    touch(path);
    return path;
  }

  @Override
  public void move(Path source, Path target, CopyOption... options) throws IOException {
    fileContents.put(MorePaths.normalize(target), fileContents.remove(MorePaths.normalize(source)));
    fileAttributes.put(MorePaths.normalize(target),
        fileAttributes.remove(MorePaths.normalize(source)));
    fileLastModifiedTimes.put(MorePaths.normalize(target),
        fileLastModifiedTimes.remove(MorePaths.normalize(source)));
  }

}
