// Copyright (C) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in project root for information.

package com.microsoft.ml.spark.lightgbm.dataset

import com.microsoft.ml.lightgbm.{SWIGTYPE_p_int, lightgbmlib, lightgbmlibConstants}

import java.util.concurrent.atomic.AtomicLong
import com.microsoft.ml.spark.lightgbm.{ColumnParams, LightGBMUtils}
import com.microsoft.ml.spark.lightgbm.dataset.DatasetUtils.{addFeaturesToChunkedArray, getRowAsDoubleArray}
import com.microsoft.ml.spark.lightgbm.swig._
import org.apache.spark.ml.linalg.SQLDataTypes.VectorType
import org.apache.spark.ml.linalg.{DenseVector, SparseVector}
import org.apache.spark.sql.Row
import org.apache.spark.sql.types.StructType

import scala.collection.mutable.ListBuffer

private[lightgbm] object ChunkedArrayUtils {
  def copyChunkedArray[T: Numeric](chunkedArray: ChunkedArray[T],
                                   mainArray: BaseSwigArray[T],
                                   threadRowStartIndex: Long,
                                   chunkSize: Long): Unit = {
    val num = implicitly[Numeric[T]]
    val defaultVal = num.fromInt(-1)
    // Copy in parallel on each thread
    // First copy full chunks
    val chunkCount = chunkedArray.getChunksCount() - 1
    for (chunk <- 0L until chunkCount) {
      for (inChunkIdx <- 0L until chunkSize) {
        mainArray.setItem(threadRowStartIndex + chunk * chunkSize + inChunkIdx,
          chunkedArray.getItem(chunk, inChunkIdx, defaultVal))
      }
    }
    // Next copy filled values from last chunk only
    val lastChunkCount = chunkedArray.getLastChunkAddCount()
    for (lastChunkIdx <- 0L until lastChunkCount) {
      mainArray.setItem(threadRowStartIndex + chunkCount * chunkSize + lastChunkIdx,
        chunkedArray.getItem(chunkCount, lastChunkIdx, defaultVal))
    }
  }
}

private[lightgbm] abstract class BaseChunkedColumns(columnParams: ColumnParams,
                                                    schema: StructType,
                                                    chunkSize: Int) {
  protected val labels: FloatChunkedArray = new FloatChunkedArray(chunkSize)
  protected val weights: Option[FloatChunkedArray] = columnParams.weightColumn.map {
    _ => new FloatChunkedArray(chunkSize)
  }
  protected val initScores: Option[DoubleChunkedArray] = columnParams.initScoreColumn.map {
    _ => new DoubleChunkedArray(chunkSize)
  }
  protected val groups: ListBuffer[Any] = new ListBuffer[Any]()

  protected var rowCount = 0

  def addInitScoreColumnRow(row: Row): Unit = {
    columnParams.initScoreColumn.foreach { col =>
      if (schema(col).dataType == VectorType) {
        row.getAs[DenseVector](col).values.foreach(initScores.get.add)
        // Note: rows * # classes in multiclass case
      } else {
        initScores.get.add(row.getAs[Double](col))
      }
    }
  }

  def addGroupColumnRow(row: Row): Unit = {
    columnParams.groupColumn.foreach { col =>
      groups.append(row.getAs[Any](col))
    }
  }

  def addRows(rowsIter: Iterator[Row]): Unit = {
    while (rowsIter.hasNext) {
      rowCount += 1
      val row = rowsIter.next()
      addFeatures(row)
      labels.add(row.getDouble(schema.fieldIndex(columnParams.labelColumn)).toFloat)
      columnParams.weightColumn.foreach { col =>
        weights.get.add(row.getDouble(schema.fieldIndex(col)).toFloat)
      }
      addInitScoreColumnRow(row)
      addGroupColumnRow(row)
    }
  }

  protected def addFeatures(row: Row): Unit

  def release(): Unit = {
    // Clear memory
    labels.delete()
    weights.foreach(_.delete())
    initScores.foreach(_.delete())
  }

  def getRowCount: Long = rowCount

  def getNumInitScores: Long = this.initScores.map(_.getAddCount()).getOrElse(0L)

  def getWeights: Option[FloatChunkedArray] = weights

  def getInitScores: Option[DoubleChunkedArray] = initScores

  def getLabels: FloatChunkedArray = labels

  def getGroups: ListBuffer[Any] = groups
}

private[lightgbm] final class SparseChunkedColumns(columnParams: ColumnParams,
                                                   schema: StructType,
                                                   chunkSize: Int,
                                                   useSingleDataset: Boolean)
  extends BaseChunkedColumns(columnParams, schema, chunkSize) {

  protected var indexes = new IntChunkedArray(chunkSize)
  protected var values = new DoubleChunkedArray(chunkSize)
  protected var indexPointers = new IntChunkedArray(chunkSize)
  private var numCols = 0

  override def addRows(rowsIter: Iterator[Row]): Unit = {
    if (!useSingleDataset) {
      indexPointers.add(0)
    }
    super.addRows(rowsIter)
  }

  override protected def addFeatures(row: Row): Unit = {
    val sparseVector = row.get(schema.fieldIndex(columnParams.featuresColumn)) match {
      case dense: DenseVector => dense.toSparse
      case sparse: SparseVector => sparse
    }
    sparseVector.values.foreach(this.values.add(_))
    sparseVector.indices.foreach(this.indexes.add(_))
    setNumCols(sparseVector.size)
    indexPointers.add(sparseVector.numNonzeros)
  }

  def setNumCols(nc: Int): Unit = {
    numCols = nc
  }

  def getNumCols: Int = numCols

  def getNumIndexes: Long = indexes.getAddCount()

  def getNumIndexPointers: Long = indexPointers.getAddCount()

  def getIndexes: IntChunkedArray = indexes

  def getValues: DoubleChunkedArray = values

  def getIndptr: IntChunkedArray = indexPointers

  override def release(): Unit = {
    // Clear memory
    super.release()
    indexes.delete()
    values.delete()
    indexPointers.delete()
  }
}

private[lightgbm] final class DenseChunkedColumns(columnParams: ColumnParams,
                                                  schema: StructType,
                                                  chunkSize: Int,
                                                  val numCols: Int)
  extends BaseChunkedColumns(columnParams, schema, chunkSize) {
  var features = new DoubleChunkedArray(numCols * chunkSize)

  override protected def addFeatures(row: Row): Unit = {
    addFeaturesToChunkedArray(features, getRowAsDoubleArray(row, columnParams))
  }

  override def release(): Unit = {
    // Clear memory
    super.release()
    features.delete()
  }

  def getFeatures: DoubleChunkedArray = features
}

private[lightgbm] abstract class BaseAggregatedColumns(val chunkSize: Int) {
  protected var labels: FloatSwigArray = _
  protected var weights: Option[FloatSwigArray] = None
  protected var initScores: Option[DoubleSwigArray] = None
  protected var groups: Array[Any] = _

  /**
    * Variables for knowing how large full array should be allocated to
    */
  protected var rowCount = new AtomicLong(0L)
  protected var initScoreCount = new AtomicLong(0L)

  protected var numCols = 0

  def getRowCount: Int = rowCount.get().toInt

  def getNumCols: Int = numCols

  def getNumColsFromChunkedArray(chunkedCols: BaseChunkedColumns): Int

  def incrementCount(chunkedCols: BaseChunkedColumns): Unit = {
    rowCount.addAndGet(chunkedCols.getRowCount)
    initScoreCount.addAndGet(chunkedCols.getNumInitScores)
  }

  def addRows(chunkedCols: BaseChunkedColumns): Unit = {
    numCols = getNumColsFromChunkedArray(chunkedCols)
  }

  protected def initializeRows(chunkedCols: BaseChunkedColumns): Unit = {
    // this.numCols = numCols
    val rc = rowCount.get()
    val isc = initScoreCount.get()
    labels = new FloatSwigArray(rc)
    weights = chunkedCols.getWeights.map(_ => new FloatSwigArray(rc))
    initScores = chunkedCols.getInitScores.map(_ => new DoubleSwigArray(isc))
    initializeFeatures(chunkedCols, rc)
    groups = new Array[Any](rc.toInt)
  }

  protected def initializeFeatures(chunkedCols: BaseChunkedColumns, rowCount: Long): Unit

  def getGroups: Array[Any] = groups

  def cleanup(): Unit = {
    labels.delete()
    weights.foreach(_.delete())
    initScores.foreach(_.delete())
  }

  def generateDataset(referenceDataset: Option[LightGBMDataset], datasetParams: String): LightGBMDataset

}

private[lightgbm] trait DisjointAggregatedColumns extends BaseAggregatedColumns {
  def addFeatures(chunkedCols: BaseChunkedColumns): Unit

  /** Adds the rows to the internal data structure.
    */
  override def addRows(chunkedCols: BaseChunkedColumns): Unit = {
    super.addRows(chunkedCols)
    initializeRows(chunkedCols)
    // Coalesce to main arrays passed to dataset create
    chunkedCols.getLabels.coalesceTo(this.labels)
    chunkedCols.getWeights.foreach(_.coalesceTo(this.weights.get))
    chunkedCols.getInitScores.foreach(_.coalesceTo(this.initScores.get))
    this.addFeatures(chunkedCols)
    chunkedCols.getGroups.copyToArray(groups)
  }
}

private[lightgbm] trait SyncAggregatedColumns extends BaseAggregatedColumns {
  /**
    * Variables for current thread to use in order to update common arrays in parallel
    */
  protected var threadRowStartIndex = new AtomicLong(0L)
  protected var threadInitScoreStartIndex = new AtomicLong(0L)

  /** Adds the rows to the internal data structure.
    */
  override def addRows(chunkedCols: BaseChunkedColumns): Unit = {
    super.addRows(chunkedCols)
    parallelInitializeRows(chunkedCols)
    parallelizedCopy(chunkedCols)
  }

  private def parallelInitializeRows(chunkedCols: BaseChunkedColumns): Unit = {
    // Initialize arrays if they are not defined - first thread to get here does the initialization for all of them
    if (labels == null) {
      this.synchronized {
        if (labels == null) {
          initializeRows(chunkedCols)
        }
      }
    }
  }

  protected def updateThreadLocalIndices(chunkedCols: BaseChunkedColumns, threadRowStartIndex: Long): List[Long]

  protected def parallelizeFeaturesCopy(chunkedCols: BaseChunkedColumns, featureIndexes: List[Long]): Unit

  private def parallelizedCopy(chunkedCols: BaseChunkedColumns): Unit = {
    // Parallelized copy to common arrays
    var threadRowStartIndex = 0L
    var threadInitScoreStartIndex = 0L
    val featureIndexes =
      this.synchronized {
        val labelsSize = chunkedCols.getLabels.getAddCount()
        threadRowStartIndex = this.threadRowStartIndex.getAndAdd(labelsSize.toInt)
        val initScoreSize = chunkedCols.getInitScores.map(_.getAddCount())
        initScoreSize.foreach(size => threadInitScoreStartIndex = this.threadInitScoreStartIndex.getAndAdd(size))
        updateThreadLocalIndices(chunkedCols, threadRowStartIndex)
      }
    ChunkedArrayUtils.copyChunkedArray(chunkedCols.getLabels, labels, threadRowStartIndex, chunkSize)
    chunkedCols.getWeights.foreach {
      weightChunkedArray =>
        ChunkedArrayUtils.copyChunkedArray(weightChunkedArray, weights.get, threadRowStartIndex,
          chunkSize)
    }
    chunkedCols.getInitScores.foreach {
      initScoreChunkedArray =>
        ChunkedArrayUtils.copyChunkedArray(initScoreChunkedArray, initScores.get,
          threadInitScoreStartIndex, chunkSize)
    }
    parallelizeFeaturesCopy(chunkedCols, featureIndexes)
    chunkedCols.getGroups.copyToArray(groups, threadRowStartIndex.toInt)
    // rewrite array reference for volatile arrays, see: https://www.javamex.com/tutorials/volatile_arrays.shtml
    this.synchronized {
      groups = groups
    }
  }
}

private[lightgbm] abstract class BaseDenseAggregatedColumns(chunkSize: Int) extends BaseAggregatedColumns(chunkSize) {
  protected var features: DoubleSwigArray = _

  def getNumColsFromChunkedArray(chunkedCols: BaseChunkedColumns): Int = {
    chunkedCols.asInstanceOf[DenseChunkedColumns].numCols
  }

  protected def initializeFeatures(chunkedCols: BaseChunkedColumns, rowCount: Long): Unit = {
    features = new DoubleSwigArray(numCols * rowCount)
  }

  def getFeatures: DoubleSwigArray = features

  def generateDataset(referenceDataset: Option[LightGBMDataset], datasetParams: String): LightGBMDataset = {
    val pointer = lightgbmlib.voidpp_handle()
    try {
      // Generate the dataset for features
      LightGBMUtils.validate(lightgbmlib.LGBM_DatasetCreateFromMat(
        lightgbmlib.double_to_voidp_ptr(features.array),
        lightgbmlibConstants.C_API_DTYPE_FLOAT64,
        rowCount.get().toInt,
        numCols,
        1,
        datasetParams,
        referenceDataset.map(_.datasetPtr).orNull,
        pointer), "Dataset create")
    } finally {
      lightgbmlib.delete_doubleArray(features.array)
    }
    val dataset = new LightGBMDataset(lightgbmlib.voidpp_value(pointer))
    dataset.addFloatField(labels.array, "label", getRowCount)
    weights.map(_.array).foreach(dataset.addFloatField(_, "weight", getRowCount))
    initScores.map(_.array).foreach(dataset.addDoubleField(_, "init_score", getRowCount))
    dataset
  }

}

private[lightgbm] final class DenseAggregatedColumns(chunkSize: Int)
  extends BaseDenseAggregatedColumns(chunkSize) with DisjointAggregatedColumns {

  def addFeatures(chunkedCols: BaseChunkedColumns): Unit = {
    chunkedCols.asInstanceOf[DenseChunkedColumns].getFeatures.coalesceTo(features)
  }

}

/** Defines class for aggregating rows to a single structure before creating the native LightGBMDataset.
  *
  * @param chunkSize The chunk size for the chunked arrays.
  */
private[lightgbm] final class DenseSyncAggregatedColumns(chunkSize: Int)
  extends BaseDenseAggregatedColumns(chunkSize) with SyncAggregatedColumns {
  protected def updateThreadLocalIndices(chunkedCols: BaseChunkedColumns, threadRowStartIndex: Long): List[Long] = {
    List(threadRowStartIndex)
  }

  protected def parallelizeFeaturesCopy(chunkedCols: BaseChunkedColumns, featureIndexes: List[Long]): Unit = {
    ChunkedArrayUtils.copyChunkedArray(chunkedCols.asInstanceOf[DenseChunkedColumns].getFeatures,
     features, featureIndexes.head * numCols, chunkSize)
  }

}

private[lightgbm] abstract class BaseSparseAggregatedColumns(chunkSize: Int)
  extends BaseAggregatedColumns(chunkSize) {
  protected var indexes: IntSwigArray = _
  protected var values: DoubleSwigArray = _
  protected var indexPointers: IntSwigArray = _

  /**
    * Aggregated variables for knowing how large full array should be allocated to
    */
  protected var indexesCount = new AtomicLong(0L)
  protected var indptrCount = new AtomicLong(0L)

  def getNumColsFromChunkedArray(chunkedCols: BaseChunkedColumns): Int = {
    chunkedCols.asInstanceOf[SparseChunkedColumns].getNumCols
  }

  override def incrementCount(chunkedCols: BaseChunkedColumns): Unit = {
    super.incrementCount(chunkedCols)
    val sparseChunkedCols = chunkedCols.asInstanceOf[SparseChunkedColumns]
    indexesCount.addAndGet(sparseChunkedCols.getNumIndexes)
    indptrCount.addAndGet(sparseChunkedCols.getNumIndexPointers)
  }

  protected def initializeFeatures(chunkedCols: BaseChunkedColumns, rowCount: Long): Unit = {
    val indexesCount = this.indexesCount.get()
    val indptrCount = this.indptrCount.get()
    indexes = new IntSwigArray(indexesCount)
    values = new DoubleSwigArray(indexesCount)
    indexPointers = new IntSwigArray(indptrCount)
    indexPointers.setItem(0, 0)
  }

  def getIndexes: IntSwigArray = indexes

  def getValues: DoubleSwigArray = values

  def getIndexPointers: IntSwigArray = indexPointers

  override def cleanup(): Unit = {
    labels.delete()
    weights.foreach(_.delete())
    initScores.foreach(_.delete())
    values.delete()
    indexes.delete()
    indexPointers.delete()
  }

  private def indexPointerArrayIncrement(indptrArray: SWIGTYPE_p_int): Unit = {
    // Update indptr array indexes in sparse matrix
    (1L until indptrCount.get()).foreach { index =>
      val indptrPrevValue = lightgbmlib.intArray_getitem(indptrArray, index - 1)
      val indptrCurrValue = lightgbmlib.intArray_getitem(indptrArray, index)
      lightgbmlib.intArray_setitem(indptrArray, index, indptrPrevValue + indptrCurrValue)
    }
  }

  def generateDataset(referenceDataset: Option[LightGBMDataset], datasetParams: String): LightGBMDataset = {
    indexPointerArrayIncrement(getIndexPointers.array)
    val pointer = lightgbmlib.voidpp_handle()
    // Generate the dataset for features
    LightGBMUtils.validate(lightgbmlib.LGBM_DatasetCreateFromCSR(
      lightgbmlib.int_to_voidp_ptr(indexPointers.array),
      lightgbmlibConstants.C_API_DTYPE_INT32,
      indexes.array,
      lightgbmlib.double_to_voidp_ptr(values.array),
      lightgbmlibConstants.C_API_DTYPE_FLOAT64,
      indptrCount.get(),
      indexesCount.get(),
      numCols,
      datasetParams,
      referenceDataset.map(_.datasetPtr).orNull,
      pointer), "Dataset create")
    val dataset = new LightGBMDataset(lightgbmlib.voidpp_value(pointer))
    dataset.addFloatField(labels.array, "label", getRowCount)
    weights.map(_.array).foreach(dataset.addFloatField(_, "weight", getRowCount))
    initScores.map(_.array).foreach(dataset.addDoubleField(_, "init_score", getRowCount))
    dataset
  }

}

/** Defines class for aggregating rows to a single structure before creating the native LightGBMDataset.
  *
  * @param chunkSize The chunk size for the chunked arrays.
  */
private[lightgbm] final class SparseAggregatedColumns(chunkSize: Int)
  extends BaseSparseAggregatedColumns(chunkSize) with DisjointAggregatedColumns {

  /** Adds the indexes, values and indptr to the internal data structure.
    */
  def addFeatures(chunkedCols: BaseChunkedColumns): Unit = {
    val sparseChunkedColumns = chunkedCols.asInstanceOf[SparseChunkedColumns]
    sparseChunkedColumns.getIndexes.coalesceTo(indexes)
    sparseChunkedColumns.getValues.coalesceTo(values)
    sparseChunkedColumns.getIndptr.coalesceTo(indexPointers)
  }
}

/** Defines class for aggregating rows to a single structure before creating the native LightGBMDataset.
  *
  * @param chunkSize The chunk size for the chunked arrays.
  */
private[lightgbm] final class SparseSyncAggregatedColumns(chunkSize: Int)
  extends BaseSparseAggregatedColumns(chunkSize) with SyncAggregatedColumns {
  /**
    * Variables for current thread to use in order to update common arrays in parallel
    */
  protected val threadIndexesStartIndex = new AtomicLong(0L)
  protected val threadIndptrStartIndex = new AtomicLong(1L)

  override protected def initializeRows(chunkedCols: BaseChunkedColumns): Unit = {
    // Add extra 0 for start of indptr in parallel case
    this.indptrCount.addAndGet(1L)
    super.initializeRows(chunkedCols)
  }

  protected def updateThreadLocalIndices(chunkedCols: BaseChunkedColumns, threadRowStartIndex: Long): List[Long] = {
    val sparseChunkedCols = chunkedCols.asInstanceOf[SparseChunkedColumns]
    val indexesSize = sparseChunkedCols.getIndexes.getAddCount()
    val threadIndexesStartIndex = this.threadIndexesStartIndex.getAndAdd(indexesSize)

    val indPtrSize = sparseChunkedCols.getIndptr.getAddCount()
    val threadIndPtrStartIndex = this.threadIndptrStartIndex.getAndAdd(indPtrSize)
    List(threadIndexesStartIndex, threadIndPtrStartIndex)
  }

  protected def parallelizeFeaturesCopy(chunkedCols: BaseChunkedColumns, featureIndexes: List[Long]): Unit = {
    val sparseChunkedCols = chunkedCols.asInstanceOf[SparseChunkedColumns]
    val threadIndexesStartIndex = featureIndexes(0)
    val threadIndPtrStartIndex = featureIndexes(1)
    ChunkedArrayUtils.copyChunkedArray(
      sparseChunkedCols.getIndexes, indexes, threadIndexesStartIndex, chunkSize)
    ChunkedArrayUtils.copyChunkedArray(
      sparseChunkedCols.getValues, values, threadIndexesStartIndex, chunkSize)
    ChunkedArrayUtils.copyChunkedArray(
      sparseChunkedCols.getIndptr, indexPointers, threadIndPtrStartIndex, chunkSize)
  }

}
