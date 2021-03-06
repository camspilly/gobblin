/*
 * Copyright (C) 2014-2016 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.
 */
package gobblin.data.management.conversion.hive.converter;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;

import lombok.extern.slf4j.Slf4j;

import org.apache.avro.Schema;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hive.metastore.IMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.NoSuchObjectException;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.ql.metadata.Partition;
import org.apache.thrift.TException;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import gobblin.configuration.WorkUnitState;
import gobblin.converter.Converter;
import gobblin.converter.DataConversionException;
import gobblin.converter.SingleRecordIterable;
import gobblin.data.management.conversion.hive.dataset.ConvertibleHiveDataset;
import gobblin.data.management.conversion.hive.dataset.ConvertibleHiveDataset.ConversionConfig;
import gobblin.data.management.conversion.hive.entities.QueryBasedHiveConversionEntity;
import gobblin.data.management.conversion.hive.entities.QueryBasedHivePublishEntity;
import gobblin.data.management.conversion.hive.events.EventWorkunitUtils;
import gobblin.data.management.conversion.hive.query.HiveAvroORCQueryGenerator;
import gobblin.data.management.copy.hive.HiveDatasetFinder;
import gobblin.data.management.copy.hive.HiveUtils;
import gobblin.hive.HiveMetastoreClientPool;
import gobblin.metrics.event.sla.SlaEventKeys;
import gobblin.util.AutoReturnableObject;
import gobblin.util.HadoopUtils;


/**
 * Builds the Hive avro to ORC conversion query. The record type for this converter is {@link QueryBasedHiveConversionEntity}. A {@link QueryBasedHiveConversionEntity}
 * can be a hive table or a hive partition.
 * <p>
 * Concrete subclasses define the semantics of Avro to ORC conversion for a specific ORC format by providing {@link ConversionConfig}s.
 * </p>
 */
@Slf4j
public abstract class AbstractAvroToOrcConverter extends Converter<Schema, Schema, QueryBasedHiveConversionEntity, QueryBasedHiveConversionEntity> {

  /***
   * Subdirectory within destination ORC table directory to publish data
   */
  private static final String PUBLISHED_TABLE_SUBDIRECTORY = "final";

  /**
   * Hive runtime property key names for tracking
   */
  private static final String GOBBLIN_DATASET_URN_KEY = "gobblin.datasetUrn";
  private static final String GOBBLIN_PARTITION_NAME_KEY = "gobblin.partitionName";
  private static final String GOBBLIN_WORKUNIT_CREATE_TIME_KEY = "gobblin.workunitCreateTime";

  protected final FileSystem fs;

  /**
   * Supported destination ORC formats
   */
  protected enum OrcFormats {
    FLATTENED_ORC("flattenedOrc"),
    NESTED_ORC("nestedOrc");
    private final String configPrefix;

    OrcFormats(String configPrefix) {
      this.configPrefix = configPrefix;
    }

    public String getConfigPrefix() {
      return this.configPrefix;
    }
  }

  /**
   * list of partitions that a partition has replaced. E.g. list of hourly partitons for a daily partition
   */
  public static final String REPLACED_PARTITIONS_HIVE_METASTORE_KEY = "gobblin.replaced.partitions";

  /**
   * The dataset being converted.
   */
  protected ConvertibleHiveDataset hiveDataset;

  /**
   * Subclasses can convert the {@link Schema} if required.
   *
   * {@inheritDoc}
   * @see gobblin.converter.Converter#convertSchema(java.lang.Object, gobblin.configuration.WorkUnitState)
   */
  @Override
  public abstract Schema convertSchema(Schema inputSchema, WorkUnitState workUnit);

  /**
   * <p>
   * This method is called by {@link AbstractAvroToOrcConverter#convertRecord(Schema, QueryBasedHiveConversionEntity, WorkUnitState)} before building the
   * conversion query. Subclasses can find out if conversion is enabled for their format by calling
   * {@link ConvertibleHiveDataset#getConversionConfigForFormat(String)} on the <code>hiveDataset</code>.<br>
   * Available ORC formats are defined by the enum {@link OrcFormats}
   * </p>
   * <p>
   * If this method returns false, no Avro to to ORC conversion queries will be built for the ORC format.
   * </p>
   * @return true if conversion is required. false otherwise
   */
  protected abstract boolean hasConversionConfig();

  /**
   * Get the {@link ConversionConfig} required for building the Avro to ORC conversion query
   * @return Conversion config
   */
  protected abstract ConversionConfig getConversionConfig();

  public AbstractAvroToOrcConverter() {
    try {
      this.fs = FileSystem.get(HadoopUtils.newConfiguration());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Populate the avro to orc conversion queries. The Queries will be added to {@link QueryBasedHiveConversionEntity#getQueries()}
   */
  @Override
  public Iterable<QueryBasedHiveConversionEntity> convertRecord(Schema outputAvroSchema, QueryBasedHiveConversionEntity conversionEntity, WorkUnitState workUnit)
      throws DataConversionException {

    Preconditions.checkNotNull(outputAvroSchema, "Avro schema must not be null");
    Preconditions.checkNotNull(conversionEntity, "Conversion entity must not be null");
    Preconditions.checkNotNull(workUnit, "Workunit state must not be null");
    Preconditions.checkNotNull(conversionEntity.getHiveTable(), "Hive table within conversion entity must not be null");

    EventWorkunitUtils.setBeginDDLBuildTimeMetadata(workUnit, System.currentTimeMillis());

    this.hiveDataset = conversionEntity.getConvertibleHiveDataset();

    if (!hasConversionConfig()) {
      return new SingleRecordIterable<>(conversionEntity);
    }

    // Avro table name and location
    String avroTableName = conversionEntity.getHiveTable().getTableName();

    // ORC table name and location
    String orcTableName = getConversionConfig().getDestinationTableName();
    String orcStagingTableName = getOrcStagingTableName(getConversionConfig().getDestinationStagingTableName());
    String orcTableDatabase = getConversionConfig().getDestinationDbName();
    String orcDataLocation = getOrcDataLocation();
    String orcStagingDataLocation = getOrcStagingDataLocation(orcStagingTableName);
    boolean isEvolutionEnabled = getConversionConfig().isEvolutionEnabled();
    Pair<Optional<Table>, Optional<List<Partition>>> destinationMeta = getDestinationTableMeta(orcTableDatabase,
        orcTableName, workUnit);
    Optional<Table> destinationTableMeta = destinationMeta.getLeft();

    // Optional
    Optional<List<String>> clusterBy =
        getConversionConfig().getClusterBy().isEmpty()
            ? Optional.<List<String>> absent()
            : Optional.of(getConversionConfig().getClusterBy());
    Optional<Integer> numBuckets = getConversionConfig().getNumBuckets();
    Optional<Integer> rowLimit = getConversionConfig().getRowLimit();
    Properties tableProperties = getConversionConfig().getDestinationTableProperties();

    // Partition dir hint helps create different directory for hourly and daily partition with same timestamp, such as:
    // .. daily_2016-01-01-00 and hourly_2016-01-01-00
    // This helps existing hourly data from not being deleted at the time of roll up, and so Hive queries in flight
    // .. do not fail
    List<String> sourceDataPathIdentifier = getConversionConfig().getSourceDataPathIdentifier();

    // Populate optional partition info
    Map<String, String> partitionsDDLInfo = Maps.newHashMap();
    Map<String, String> partitionsDMLInfo = Maps.newHashMap();
    populatePartitionInfo(conversionEntity, partitionsDDLInfo, partitionsDMLInfo);

    /*
     * Create ORC data location with the same permissions as Avro data
     *
     * Note that hive can also automatically create the non-existing directories but it does not
     * seem to create it with the desired permissions.
     * According to hive docs permissions for newly created directories/files can be controlled using uMask like,
     *
     * SET hive.warehouse.subdir.inherit.perms=false;
     * SET fs.permissions.umask-mode=022;
     * Upon testing, this did not work
     */
    try {
      FsPermission sourceDataPermission =
          this.fs.getFileStatus(conversionEntity.getHiveTable().getDataLocation()).getPermission();
      if (!this.fs.mkdirs(new Path(getConversionConfig().getDestinationDataPath()), sourceDataPermission)) {
        throw new RuntimeException(String.format("Failed to create path %s with permissions %s", new Path(
            getConversionConfig().getDestinationDataPath()), sourceDataPermission));
      } else {
        this.fs.setPermission(new Path(getConversionConfig().getDestinationDataPath()), sourceDataPermission);
        log.info(String.format("Created %s with permissions %s", new Path(getConversionConfig()
            .getDestinationDataPath()), sourceDataPermission));
      }
    } catch (IOException e) {
      Throwables.propagate(e);
    }

    // Set hive runtime properties from conversion config
    for (Map.Entry<Object, Object> entry : getConversionConfig().getHiveRuntimeProperties().entrySet()) {
      conversionEntity.getQueries().add(String.format("SET %s=%s", entry.getKey(), entry.getValue()));
    }
    // Set hive runtime properties for tracking
    conversionEntity.getQueries().add(String.format("SET %s=%s", GOBBLIN_DATASET_URN_KEY,
        conversionEntity.getHiveTable().getCompleteName()));
    if (conversionEntity.getHivePartition().isPresent()) {
      conversionEntity.getQueries().add(String.format("SET %s=%s", GOBBLIN_PARTITION_NAME_KEY,
          conversionEntity.getHivePartition().get().getCompleteName()));
    }
    conversionEntity.getQueries().add(String
        .format("SET %s=%s", GOBBLIN_WORKUNIT_CREATE_TIME_KEY,
            workUnit.getWorkunit().getProp(SlaEventKeys.ORIGIN_TS_IN_MILLI_SECS_KEY)));

    // Create DDL statement for table
    Map<String, String> hiveColumns = new HashMap<>();
    String createStagingTableDDL =
        HiveAvroORCQueryGenerator.generateCreateTableDDL(outputAvroSchema,
            orcStagingTableName,
            orcStagingDataLocation,
            Optional.of(orcTableDatabase),
            Optional.of(partitionsDDLInfo),
            clusterBy,
            Optional.<Map<String, HiveAvroORCQueryGenerator.COLUMN_SORT_ORDER>>absent(),
            numBuckets,
            Optional.<String>absent(),
            Optional.<String>absent(),
            Optional.<String>absent(),
            tableProperties,
            isEvolutionEnabled,
            destinationTableMeta,
            hiveColumns);
    conversionEntity.getQueries().add(createStagingTableDDL);
    log.debug("Create staging table DDL: " + createStagingTableDDL);

    // Create DDL statement for partition
    String orcStagingDataPartitionDirName = getOrcStagingDataPartitionDirName(conversionEntity, sourceDataPathIdentifier);
    String orcStagingDataPartitionLocation = orcStagingDataLocation + Path.SEPARATOR + orcStagingDataPartitionDirName;
    if (partitionsDMLInfo.size() > 0) {
      List<String> createStagingPartitionDDL =
          HiveAvroORCQueryGenerator.generateCreatePartitionDDL(orcTableDatabase,
              orcStagingTableName,
              orcStagingDataPartitionLocation,
              partitionsDMLInfo);

      conversionEntity.getQueries().addAll(createStagingPartitionDDL);
      log.debug("Create staging partition DDL: " + createStagingPartitionDDL);
    }

    // Create DML statement
    String insertInORCStagingTableDML =
        HiveAvroORCQueryGenerator
            .generateTableMappingDML(conversionEntity.getHiveTable().getAvroSchema(),
                outputAvroSchema,
                avroTableName,
                orcStagingTableName,
                Optional.of(conversionEntity.getHiveTable().getDbName()),
                Optional.of(orcTableDatabase),
                Optional.of(partitionsDMLInfo),
                Optional.<Boolean>absent(),
                Optional.<Boolean>absent(),
                isEvolutionEnabled,
                destinationTableMeta,
                rowLimit);
    conversionEntity.getQueries().add(insertInORCStagingTableDML);
    log.debug("Conversion staging DML: " + insertInORCStagingTableDML);

    // TODO: Split this method into two (conversion and publish)
    // Addition to WUS for Staging publish:
    // A. Evolution turned on:
    //    1. If table does not exists: simply create it (now it should exist)
    //    2. If table exists:
    //      2.1 Evolve table (alter table)
    //      2.2 If snapshot table:
    //          2.2.1 Delete data in final table directory
    //          2.2.2 Move data from staging to final table directory
    //          2.2.3 Drop this staging table and delete directories
    //      2.3 If partitioned table, move partitions from staging to final table; for all partitions:
    //          2.3.1 Drop if exists partition in final table
    //          2.3.2 Move partition directory
    //          2.3.3 Create partition with location
    //          2.3.4 Drop this staging table and delete directories
    // B. Evolution turned off:
    //    1. If table does not exists: simply create it (now it should exist)
    //    2. If table exists:
    //      2.1 Do not evolve table
    //      2.2 If snapshot table:
    //          2.2.1 Delete data in final table directory
    //          2.2.2 Move data from staging to final table directory
    //          2.2.3 Drop this staging table and delete directories
    //      2.3 If partitioned table, move partitions from staging to final table; for all partitions:
    //          2.3.1 Drop if exists partition in final table
    //          2.3.2 Move partition directory
    //          2.3.3 Create partition with location
    //          2.3.4 Drop this staging table and delete directories
    // Note: The queries below also serve as compatibility check module before conversion, an incompatible
    //      .. schema throws a Runtime exeption, hence preventing further execution
    QueryBasedHivePublishEntity publishEntity = new QueryBasedHivePublishEntity();
    List<String> publishQueries = publishEntity.getPublishQueries();
    Map<String, String> publishDirectories = publishEntity.getPublishDirectories();
    List<String> cleanupQueries = publishEntity.getCleanupQueries();
    List<String> cleanupDirectories = publishEntity.getCleanupDirectories();

    // Step:
    // A.1, B.1: If table does not exists, simply create it
    if (!destinationTableMeta.isPresent()) {
      String createTargetTableDDL =
          HiveAvroORCQueryGenerator.generateCreateTableDDL(outputAvroSchema,
              orcTableName,
              orcDataLocation,
              Optional.of(orcTableDatabase),
              Optional.of(partitionsDDLInfo),
              clusterBy,
              Optional.<Map<String, HiveAvroORCQueryGenerator.COLUMN_SORT_ORDER>>absent(),
              numBuckets,
              Optional.<String>absent(),
              Optional.<String>absent(),
              Optional.<String>absent(),
              tableProperties,
              isEvolutionEnabled,
              destinationTableMeta,
              new HashMap<String, String>());
      publishQueries.add(createTargetTableDDL);
      log.debug("Create final table DDL: " + createTargetTableDDL);
    }

    // Step:
    // A.2.1: If table pre-exists (destinationTableMeta would be present), evolve table
    // B.2.1: No-op
    List<String> evolutionDDLs = HiveAvroORCQueryGenerator.generateEvolutionDDL(orcStagingTableName,
        orcTableName,
        Optional.of(orcTableDatabase),
        Optional.of(orcTableDatabase),
        outputAvroSchema,
        isEvolutionEnabled,
        hiveColumns,
        destinationTableMeta);
    log.debug("Evolve final table DDLs: " + evolutionDDLs);
    EventWorkunitUtils.setEvolutionMetadata(workUnit, evolutionDDLs);

    publishQueries.addAll(evolutionDDLs);


    if (partitionsDDLInfo.size() == 0) {
      // Step:
      // A.2.2, B.2.2: Snapshot table

      // Step:
      // A.2.2.1, B.2.2.1: Delete data in final table directory
      // A.2.2.2, B.2.2.2: Move data from staging to final table directory
      log.info("Snapshot directory to move: " + orcStagingDataLocation + " to: " + orcDataLocation);
      publishDirectories.put(orcStagingDataLocation, orcDataLocation);

      // Step:
      // A.2.2.3, B.2.2.3: Drop this staging table and delete directories
      String dropStagingTableDDL = HiveAvroORCQueryGenerator.generateDropTableDDL(orcTableDatabase, orcStagingTableName);

      log.debug("Drop staging table DDL: " + dropStagingTableDDL);
      cleanupQueries.add(dropStagingTableDDL);

      // Delete: orcStagingDataLocation
      log.info("Staging table directory to delete: " + orcStagingDataLocation);
      cleanupDirectories.add(orcStagingDataLocation);

    } else {
      // Step:
      // A.2.3, B.2.3: If partitioned table, move partitions from staging to final table; for all partitions:

      // Step:
      // A.2.3.1, B.2.3.1: Drop if exists partition in final table
      List<String> dropPartitionsDDL =
          HiveAvroORCQueryGenerator.generateDropPartitionsDDL(orcTableDatabase,
              orcTableName,
              partitionsDMLInfo);
      log.debug("Drop partitions if exist in final table: " + dropPartitionsDDL);
      publishQueries.addAll(dropPartitionsDDL);

      // Step:
      // A.2.3.2, B.2.3.2: Move partition directory
      // Move: orcStagingDataPartitionLocation to: orcFinalDataPartitionLocation
      String orcFinalDataPartitionLocation = orcDataLocation + Path.SEPARATOR + orcStagingDataPartitionDirName;
      log.info("Partition directory to move: " + orcStagingDataPartitionLocation + " to: " + orcFinalDataPartitionLocation);
      publishDirectories.put(orcStagingDataPartitionLocation, orcFinalDataPartitionLocation);

      // Step:
      // A.2.3.3, B.2.3.3: Create partition with location
      String orcDataPartitionLocation = orcDataLocation + Path.SEPARATOR + orcStagingDataPartitionDirName;
      List<String> createFinalPartitionDDL =
          HiveAvroORCQueryGenerator.generateCreatePartitionDDL(orcTableDatabase,
              orcTableName,
              orcDataPartitionLocation,
              partitionsDMLInfo);

      log.debug("Create final partition DDL: " + createFinalPartitionDDL);
      publishQueries.addAll(createFinalPartitionDDL);

      // Step:
      // A.2.3.4, B.2.3.4: Drop this staging table and delete directories
      String dropStagingTableDDL = HiveAvroORCQueryGenerator.generateDropTableDDL(orcTableDatabase, orcStagingTableName);

      log.debug("Drop staging table DDL: " + dropStagingTableDDL);
      cleanupQueries.add(dropStagingTableDDL);

      // Delete: orcStagingDataLocation
      log.info("Staging table directory to delete: " + orcStagingDataLocation);
      cleanupDirectories.add(orcStagingDataLocation);
    }

    /*
     * Drop the replaced partitions if any. This is required in case the partition being converted is derived from
     * several other partitions. E.g. Daily partition is a replacement of hourly partitions of the same day. When daily
     * partition is converted to ORC all it's hourly ORC partitions need to be dropped.
     */
    publishQueries.addAll(HiveAvroORCQueryGenerator.generateDropPartitionsDDL(orcTableDatabase,
        orcTableName,
        getDropPartitionsDDLInfo(conversionEntity)));

    HiveAvroORCQueryGenerator.serializePublishCommands(workUnit, publishEntity);
    log.debug("Publish partition entity: " + publishEntity);


    log.debug("Conversion Query " + conversionEntity.getQueries());

    EventWorkunitUtils.setEndDDLBuildTimeMetadata(workUnit, System.currentTimeMillis());

    return new SingleRecordIterable<>(conversionEntity);
  }

  /***
   * Get the staging table name for current converter. Each converter creates its own staging table.
   * @param stagingTableNamePrefix for the staging table for this converter.
   * @return Staging table name.
   */
  private String getOrcStagingTableName(String stagingTableNamePrefix) {
    int randomNumber = new Random().nextInt(10);
    String uniqueStagingTableQualifier = String.format("%s%s", System.currentTimeMillis(), randomNumber);

    return stagingTableNamePrefix + "_" + uniqueStagingTableQualifier;
  }

  /***
   * Get the ORC partition directory name of the format: [hourly_][daily_]<partitionSpec1>[partitionSpec ..]
   * @param conversionEntity Conversion entity.
   * @param sourceDataPathIdentifier Hints to look in source partition location to prefix the partition dir name
   *                               such as hourly or daily.
   * @return Partition directory name.
   */
  private String getOrcStagingDataPartitionDirName(QueryBasedHiveConversionEntity conversionEntity,
      List<String> sourceDataPathIdentifier) {

    if (conversionEntity.getHivePartition().isPresent()) {
      StringBuilder dirNamePrefix = new StringBuilder();
      String sourceHivePartitionLocation = conversionEntity.getHivePartition().get().getDataLocation().toString();
      if (null != sourceDataPathIdentifier && null != sourceHivePartitionLocation) {
        for (String hint : sourceDataPathIdentifier) {
          if (sourceHivePartitionLocation.toLowerCase().contains(hint.toLowerCase())) {
            dirNamePrefix.append(hint.toLowerCase()).append("_");
          }
        }
      }

      return dirNamePrefix + conversionEntity.getHivePartition().get().getName();
    } else {
      return StringUtils.EMPTY;
    }
  }

  /***
   * Get the ORC final table location of format: <ORC final table location>/final
   * @return ORC final table location.
   */
  private String getOrcDataLocation() {
    String orcDataLocation = getConversionConfig().getDestinationDataPath();

    return orcDataLocation + Path.SEPARATOR + PUBLISHED_TABLE_SUBDIRECTORY;
  }

  /***
   * Get the ORC staging table location of format: <ORC final table location>/<ORC staging table name>
   * @param orcStagingTableName ORC staging table name.
   * @return ORC staging table location.
   */
  private String getOrcStagingDataLocation(String orcStagingTableName) {
    String orcDataLocation = getConversionConfig().getDestinationDataPath();

    return orcDataLocation + Path.SEPARATOR + orcStagingTableName;
  }


  @VisibleForTesting
  public static List<Map<String, String>> getDropPartitionsDDLInfo(QueryBasedHiveConversionEntity conversionEntity) {
    if (!conversionEntity.getHivePartition().isPresent()) {
      return Collections.emptyList();
    }

    return getDropPartitionsDDLInfo(conversionEntity.getHivePartition().get());

  }

  /**
   * Parse the {@link #REPLACED_PARTITIONS_HIVE_METASTORE_KEY} from partition parameters to returns DDLs for all the partitions to be
   * dropped.
   *
   * @return A {@link List} of partitions to be dropped. Each element of the list is a {@link Map} which maps a partition's
   * key and value.
   *
   */
  public static List<Map<String, String>> getDropPartitionsDDLInfo(Partition hivePartition) {
    List<Map<String, String>> replacedPartitionsDDLInfo = Lists.newArrayList();
    List<FieldSchema> partitionKeys = hivePartition.getTable().getPartitionKeys();

    if (StringUtils.isNotBlank(hivePartition.getParameters().get(REPLACED_PARTITIONS_HIVE_METASTORE_KEY))) {

      // Partitions are separated by "|"
      for (String partitionsInfoString : Splitter.on("|").omitEmptyStrings().split(hivePartition.getParameters().get(REPLACED_PARTITIONS_HIVE_METASTORE_KEY))) {

        // Values for a partition are separated by ","
        List<String> partitionValues = Splitter.on(",").omitEmptyStrings().trimResults().splitToList(partitionsInfoString);

        // Do not drop partition the being processed. Sometimes a partition may have replaced another partition of the same values.
        if (!partitionValues.equals(hivePartition.getValues())) {
          ImmutableMap.Builder<String, String> partitionDDLInfoMap = ImmutableMap.builder();
          for (int i = 0; i < partitionKeys.size(); i++) {
            partitionDDLInfoMap.put(partitionKeys.get(i).getName(), partitionValues.get(i));
          }
          replacedPartitionsDDLInfo.add(partitionDDLInfoMap.build());
        }
      }
    }
    return replacedPartitionsDDLInfo;
  }

  private void populatePartitionInfo(QueryBasedHiveConversionEntity conversionEntity, Map<String, String> partitionsDDLInfo,
      Map<String, String> partitionsDMLInfo) {
    String partitionsInfoString = null;
    String partitionsTypeString = null;

    if (conversionEntity.getHivePartition().isPresent()) {
      partitionsInfoString = conversionEntity.getHivePartition().get().getName();
      partitionsTypeString = conversionEntity.getHivePartition().get().getSchema().getProperty("partition_columns.types");
    }

    if (StringUtils.isNotBlank(partitionsInfoString) || StringUtils.isNotBlank(partitionsTypeString)) {
      if (StringUtils.isBlank(partitionsInfoString) || StringUtils.isBlank(partitionsTypeString)) {
        throw new IllegalArgumentException("Both partitions info and partitions must be present, if one is specified");
      }
      List<String> pInfo = Splitter.on(",").omitEmptyStrings().trimResults().splitToList(partitionsInfoString);
      List<String> pType = Splitter.on(",").omitEmptyStrings().trimResults().splitToList(partitionsTypeString);
      if (pInfo.size() != pType.size()) {
        throw new IllegalArgumentException("partitions info and partitions type list should of same size");
      }
      for (int i = 0; i < pInfo.size(); i++) {
        List<String> partitionInfoParts = Splitter.on("=").omitEmptyStrings().trimResults().splitToList(pInfo.get(i));
        String partitionType = pType.get(i);
        if (partitionInfoParts.size() != 2) {
          throw new IllegalArgumentException(
              String.format("Partition details should be of the format partitionName=partitionValue. Recieved: %s", pInfo.get(i)));
        }
        partitionsDDLInfo.put(partitionInfoParts.get(0), partitionType);
        partitionsDMLInfo.put(partitionInfoParts.get(0), partitionInfoParts.get(1));
      }
    }
  }

  private Pair<Optional<Table>, Optional<List<Partition>>> getDestinationTableMeta(String dbName,
      String tableName, WorkUnitState state)
      throws DataConversionException {

    Optional<Table> table = Optional.<Table>absent();
    Optional<List<Partition>> partitions = Optional.<List<Partition>>absent();

    try {
      HiveMetastoreClientPool pool = HiveMetastoreClientPool.get(state.getJobState().getProperties(),
          Optional.fromNullable(state.getJobState().getProp(HiveDatasetFinder.HIVE_METASTORE_URI_KEY)));
      try (AutoReturnableObject<IMetaStoreClient> client = pool.getClient()) {
        table = Optional.of(client.get().getTable(dbName, tableName));
        if (table.isPresent()) {
          org.apache.hadoop.hive.ql.metadata.Table qlTable = new org.apache.hadoop.hive.ql.metadata.Table(table.get());
          if (HiveUtils.isPartitioned(qlTable)) {
            partitions = Optional.of(HiveUtils.getPartitions(client.get(), qlTable, Optional.<String>absent()));
          }
        }
      }
    } catch (NoSuchObjectException e) {
      return ImmutablePair.of(table, partitions);
    } catch (IOException | TException e) {
      throw new DataConversionException("Could not fetch destination table metadata", e);
    }

    return ImmutablePair.of(table, partitions);
  }
}
