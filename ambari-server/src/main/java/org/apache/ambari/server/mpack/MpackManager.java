/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ambari.server.mpack;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.ambari.server.controller.MpackRequest;
import org.apache.ambari.server.controller.MpackResponse;
import org.apache.ambari.server.controller.spi.ResourceAlreadyExistsException;
import org.apache.ambari.server.orm.dao.MpackDAO;
import org.apache.ambari.server.orm.dao.StackDAO;
import org.apache.ambari.server.orm.entities.MpackEntity;
import org.apache.ambari.server.orm.entities.StackEntity;
import org.apache.ambari.server.state.Mpack;
import org.apache.ambari.server.state.Packlet;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;

/**
 * Manages all mpack related behavior including parsing of stacks and providing access to
 * mpack information.
 */
public class MpackManager {
  private static final String MPACK_METADATA = "mpack.json";
  private static final String MPACK_TAR_LOCATION = "staging";
  private final static Logger LOG = LoggerFactory.getLogger(MpackManager.class);
  protected Map<Long, Mpack> mpackMap = new ConcurrentHashMap<>();
  private File mpacksStaging;
  private MpackDAO mpackDAO;
  private StackDAO stackDAO;
  private File stackRoot;

  @AssistedInject
  public MpackManager(@Assisted("mpacksv2Staging") File mpacksStagingLocation, @Assisted("stackRoot") File stackRootDir, MpackDAO mpackDAOObj, StackDAO stackDAOObj) {
    mpacksStaging = mpacksStagingLocation;
    mpackDAO = mpackDAOObj;
    stackRoot = stackRootDir;
    stackDAO = stackDAOObj;

    parseMpackDirectories();

  }

  /**
   * Parses mpackdirectories during boostrap/ambari-server restart
   * Reads from /var/lib/ambari-server/mpacks-v2/
   *
   * @throws IOException
   */
  private void parseMpackDirectories() {

    try {
      for (final File dirEntry : mpacksStaging.listFiles()) {
        if (dirEntry.isDirectory()) {
          String mpackName = dirEntry.getName();

          if (!mpackName.equals(MPACK_TAR_LOCATION)) {
            for (final File file : dirEntry.listFiles()) {
              if (file.isDirectory()) {
                String mpackVersion = file.getName();
                List resultSet = mpackDAO.findByNameVersion(mpackName, mpackVersion);
                MpackEntity mpackEntity = (MpackEntity) resultSet.get(0);

                //Read the mpack.json file into Mpack Object for further use.
                String mpackJsonContents = new String((Files.readAllBytes(Paths.get(file + "/" + MPACK_METADATA))), "UTF-8");
                Gson gson = new Gson();
                Mpack existingMpack = gson.fromJson(mpackJsonContents, Mpack.class);
                mpackMap.put(mpackEntity.getMpackId(), existingMpack);
              }
            }
          }
        }
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }


  /**
   * Parses mpack.json to fetch mpack and associated packlet information and
   * stores the mpack to the database and mpackMap
   *
   * @param mpackRequest
   * @return MpackResponse
   * @throws IOException
   * @throws IllegalArgumentException
   * @throws ResourceAlreadyExistsException
   */
  public MpackResponse registerMpack(MpackRequest mpackRequest) throws IOException, IllegalArgumentException, ResourceAlreadyExistsException {

    Long mpackId;
    String mpackName = "";
    String mpackVersion = "";
    Mpack mpack = new Mpack();
    boolean isValidMetadata;
    String mpackDirectory = "";
    Path mpackTarPath;

    //Mpack registration using a software registry
    if (mpackRequest.getRegistryId() != null) {
      mpackName = mpackRequest.getMpackName();
      mpackVersion = mpackRequest.getMpackVersion();
      mpack.setRegistryId(mpackRequest.getRegistryId());

      mpackTarPath = downloadMpack(mpackRequest.getMpackUri());

      if (createMpackDirectory(mpack, mpackTarPath)) {
        isValidMetadata = validateMpackInfo(mpackName, mpackVersion, mpack.getName(), mpack.getVersion());
        if (isValidMetadata) {
          mpackDirectory = mpacksStaging + File.separator + mpack.getName() + File.separator + mpack.getVersion();
        } else {
          String message = "Incorrect information : Mismatch in - (" + mpackName + "," + mpack.getName() + ") or (" + mpackVersion + "," + mpack.getVersion() + ")";
          throw new IllegalArgumentException(message); //Mismatch in information
        }

      }
    } else {    //Mpack registration using direct download
      mpackTarPath = downloadMpack(mpackRequest.getMpackUri());

      if (createMpackDirectory(mpack, mpackTarPath)) {
        mpackDirectory = mpacksStaging + File.separator + mpack.getName() + File.separator + mpack.getVersion();
      }
    }
    extractMpackTar(mpack, mpackTarPath, mpackDirectory);
    mpack.setMpackUri(mpackRequest.getMpackUri());
    mpackId = populateDB(mpack);

    if (mpackId != null) {
      mpackMap.put(mpackId, mpack);
      mpack.setMpackId(mpackId);
      populateStackDB(mpack);
      return new MpackResponse(mpack);
    } else {
      String message = "Mpack :" + mpackRequest.getMpackName() + " version: " + mpackRequest.getMpackVersion() + " already exists in server";
      throw new ResourceAlreadyExistsException(message);
    }
  }

  /**
   * Mpack is downloaded as a tar.gz file. It is extracted into mpack-v2-staging/{mpack-name}/{mpack-version}/ directory
   *
   * @param mpack          Mpack to process
   * @param mpackTarPath   Path to mpack tarball
   * @param mpackDirectory Mpack directory
   * @throws IOException
   */
  private void extractMpackTar(Mpack mpack, Path mpackTarPath, String mpackDirectory) throws IOException {

    TarArchiveInputStream mpackTarFile = new TarArchiveInputStream(new GzipCompressorInputStream(new BufferedInputStream(new FileInputStream(new File(String.valueOf(mpackTarPath))))));
    TarArchiveEntry entry = null;
    File outputFile = null;

    //Create a loop to read every single entry in TAR file
    while ((entry = mpackTarFile.getNextTarEntry()) != null) {
      outputFile = new File(mpacksStaging, entry.getName());
      if (entry.isDirectory()) {
        LOG.debug("Attempting to write output directory" + outputFile.getAbsolutePath());
        if (!outputFile.exists()) {
          LOG.debug("Attempting to create output directory " + outputFile.getAbsolutePath());
          if (!outputFile.mkdirs()) {
            throw new IllegalStateException(String.format("Couldn't create directory %s.", outputFile.getAbsolutePath()));
          }
        }
      } else {
        LOG.debug("Creating output file %s." + outputFile.getAbsolutePath());
        final OutputStream outputFileStream = new FileOutputStream(outputFile);
        IOUtils.copy(mpackTarFile, outputFileStream);
        outputFileStream.close();
      }
    }

    mpackTarFile.close();

    String mpackTarDirectory = mpackTarPath.toString();
    Path extractedMpackDirectory = Files.move
            (Paths.get(mpacksStaging + File.separator + mpackTarDirectory.substring(mpackTarDirectory.lastIndexOf('/') + 1, mpackTarDirectory.indexOf(".tar")) + File.separator),
                    Paths.get(mpackDirectory), StandardCopyOption.REPLACE_EXISTING);

    createSymLinks(mpack);
  }

  /**
   * Reads the mpack.json file within the {mpack-name}.tar.gz file and populates Mpack object.
   * Extract the mpack-name and mpack-version from mpack.json to create the new mpack directory to hold the mpack files.
   *
   * @param mpack        Mpack to process
   * @param mpackTarPath Path to mpack tarball
   * @return boolean
   * @throws IOException
   */
  private Boolean createMpackDirectory(Mpack mpack, Path mpackTarPath) throws IOException, ResourceAlreadyExistsException {

    TarArchiveInputStream mpackTarFile = new TarArchiveInputStream(new GzipCompressorInputStream(new BufferedInputStream(new FileInputStream(new File(mpackTarPath.toString())))));
    TarArchiveEntry entry = null;
    String individualFiles;
    int offset;

    // Create a loop to read every single entry in TAR file
    while ((entry = mpackTarFile.getNextTarEntry()) != null) {
      // Get the name of the file
      individualFiles = entry.getName();
      String[] dirFile = individualFiles.split(File.separator);

      //Search for mpack.json
      String fileName = dirFile[dirFile.length - 1];
      if (fileName.contains("mpack") && fileName.contains(".json")) {
        byte[] content = new byte[(int) entry.getSize()];
        offset = 0;
        LOG.debug("Size of the File is: " + entry.getSize());
        mpackTarFile.read(content, offset, content.length - offset);

        //Read the mpack.json file into Mpack Object for further use.
        String mpackJsonContents = new String(content, "UTF-8");
        Gson gson = new Gson();
        Mpack tempMpack = gson.fromJson(mpackJsonContents, Mpack.class);
        mpack.copyFrom(tempMpack);

        mpackTarFile.close();

        //Check if the mpack already exists
        List<MpackEntity> mpackEntities = mpackDAO.findByNameVersion(mpack.getName(), mpack.getVersion());
        if (mpackEntities.size() == 0) {
          File mpackDirectory = new File(mpacksStaging + File.separator + mpack.getName());

          if (!mpackDirectory.exists()) {
            return mpackDirectory.mkdir();
          } else {
            return true;
          }
        } else {
          String message = "Mpack :" + mpack.getName() + " version: " + mpack.getVersion() + " already exists in server";
          throw new ResourceAlreadyExistsException(message);
        }
      }
    }

    return false;
  }

  /***
   * Create a linkage between the staging directory and the working directory i.e from mpacks-v2 to stackRoot.
   * This will enable StackManager to parse the newly registered mpack as part of the stacks.
   *
   * @param mpack Mpack to process
   * @throws IOException
   */
  private void createSymLinks(Mpack mpack) throws IOException {

    String stackId = mpack.getStackId();
    String[] stackMetaData = stackId.split("-");
    String stackName = stackMetaData[0];
    String stackVersion = stackMetaData[1];
    File stack = new File(stackRoot + "/" + stackName);
    Path stackPath = Paths.get(stackRoot + "/" + stackName + "/" + stackVersion);
    Path mpackPath = Paths.get(mpacksStaging + "/" + mpack.getName() + "/" + mpack.getVersion());

    if (!stack.exists()) {
      stack.mkdir();
    }
    if (Files.isSymbolicLink(stackPath)) {
      Files.delete(stackPath);
    }
    Files.createSymbolicLink(stackPath, mpackPath);
  }


  /***
   * Download the mpack from the given uri
   * @param mpackURI
   * @return
   */
  public Path downloadMpack(String mpackURI) throws IOException {

    URL url = new URL(mpackURI);
    String mpackTarFile = mpackURI.substring(mpackURI.lastIndexOf('/') + 1, mpackURI.length());
    File stagingDir = new File(mpacksStaging.toString() + File.separator + MPACK_TAR_LOCATION);
    Path targetPath = new File(stagingDir.getPath() + File.separator + mpackTarFile).toPath();

    if (!stagingDir.exists()) {
      stagingDir.mkdir();
    }

    Files.copy(url.openStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
    return targetPath;
  }


  /**
   * Compares if the user's mpack information matches the downloaded mpack information.
   *
   * @param expectedMpackName
   * @param expectedMpackVersion
   * @param actualMpackName
   * @param actualMpackVersion
   * @return boolean
   */
  protected boolean validateMpackInfo(String expectedMpackName, String expectedMpackVersion, String actualMpackName, String actualMpackVersion) {

    if (expectedMpackName.equalsIgnoreCase(actualMpackName) && expectedMpackVersion.equalsIgnoreCase(actualMpackVersion)) {
      return true;
    }
    else {
      LOG.info("Incorrect information : Mismatch in - (" + expectedMpackName + "," + actualMpackName + ") or (" + expectedMpackVersion + "," + actualMpackVersion + ")");
      return false;
    }
  }

  /**
   * Make an entry in the mpacks database for the newly registered mpack.
   *
   * @param mpack
   * @return
   * @throws IOException
   */
  protected Long populateDB(Mpack mpack) throws IOException {

    String mpackName = mpack.getName();
    String mpackVersion = mpack.getVersion();
    List resultSet = mpackDAO.findByNameVersion(mpackName, mpackVersion);

    if (resultSet.size() == 0) {
      LOG.info("Adding mpack {}-{} to the database", mpackName, mpackVersion);

      MpackEntity mpackEntity = new MpackEntity();
      mpackEntity.setMpackName(mpackName);
      mpackEntity.setMpackVersion(mpackVersion);
      mpackEntity.setMpackUri(mpack.getMpackUri());
      mpackEntity.setRegistryId(mpack.getRegistryId());

      Long mpackId = mpackDAO.create(mpackEntity);
      return mpackId;
    }
    //mpack already exists
    return null;
  }

  /***
   * Makes an entry or updates the entry in the stack table to establish a link between the mpack and the associated stack
   * @param mpack
   * @throws IOException
   */
  protected void populateStackDB(Mpack mpack) throws IOException {

    String stackId = mpack.getStackId();
    String[] stackMetaData = stackId.split("-");
    String stackName = stackMetaData[0];
    String stackVersion = stackMetaData[1];

    StackEntity stackEntity = stackDAO.find(stackName, stackVersion);
    if (stackEntity == null) {
      LOG.info("Adding stack {}-{} to the database", stackName, stackVersion);
      stackEntity = new StackEntity();

      stackEntity.setStackName(stackName);
      stackEntity.setStackVersion(stackVersion);
      stackEntity.setCurrentMpackId(mpack.getMpackId());
      stackDAO.create(stackEntity);
    } else {
      LOG.info("Updating stack {}-{} to the database", stackName, stackVersion);

      stackEntity.setCurrentMpackId(mpack.getMpackId());
      stackDAO.merge(stackEntity);
    }
  }
  /**
   * Fetches the packlet info stored in the memory for mpacks/{mpack_id} call.
   *
   * @param mpackId
   * @return ArrayList
   */
  public ArrayList<Packlet> getPacklets(Long mpackId) {

    Mpack mpack = mpackMap.get(mpackId);
    if (mpack.getPacklets() != null)
      return mpack.getPacklets();
    return null;
  }
}
