/*******************************************************************************
 *    Copyright 2015, 2016 Taylor G Smith
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *******************************************************************************/
package com.clust4j.algo;

import java.util.ArrayList;

import org.apache.commons.math3.linear.AbstractRealMatrix;
import org.apache.commons.math3.util.FastMath;

import com.clust4j.algo.NearestCentroidParameters;
import com.clust4j.except.NaNException;
import com.clust4j.log.Log.Tag.Algo;
import com.clust4j.log.LogTimer;
import com.clust4j.metrics.pairwise.Distance;
import com.clust4j.metrics.pairwise.GeometricallySeparable;
import com.clust4j.utils.EntryPair;
import com.clust4j.utils.MatUtils;
import com.clust4j.utils.VecUtils;

/**
 * <a href="https://en.wikipedia.org/wiki/K-means_clustering">KMeans clustering</a> is
 * a method of vector quantization, originally from signal processing, that is popular 
 * for cluster analysis in data mining. KMeans clustering aims to partition <i>m</i> 
 * observations into <i>k</i> clusters in which each observation belongs to the cluster 
 * with the nearest mean, serving as a prototype of the cluster. This results in 
 * a partitioning of the data space into <a href="https://en.wikipedia.org/wiki/Voronoi_cell">Voronoi cells</a>.
 * 
 * @author Taylor G Smith &lt;tgsmith61591@gmail.com&gt;
 */
final public class KMeans extends AbstractCentroidClusterer {
	private static final long serialVersionUID = 1102324012006818767L;
	final public static GeometricallySeparable DEF_DIST = Distance.EUCLIDEAN;
	final public static int DEF_MAX_ITER = 100;
	
	
	
	protected KMeans(final AbstractRealMatrix data) {
		this(data, DEF_K);
	}
	
	protected KMeans(final AbstractRealMatrix data, final int k) {
		this(data, new KMeansParameters(k));
	}
	
	protected KMeans(final AbstractRealMatrix data, final KMeansParameters planner) {
		super(data, planner);
	}
	
	
	
	
	@Override
	public String getName() {
		return "KMeans";
	}
	
	@Override
	protected KMeans fit() {
		synchronized(fitLock) {

			if(null != labels) // already fit
				return this;
			

			final LogTimer timer = new LogTimer();
			final double[][] X = data.getData();
			final double[] mean_record = MatUtils.meanRecord(X);
			final int n = data.getColumnDimension();
			final double nan = Double.NaN;
			
			
			// Corner case: K = 1 or all singular values
			if(1 == k) {
				labelFromSingularK(X);
				fitSummary.add(new Object[]{ iter, converged, tss, tss, nan, nan, timer.wallTime() });
				sayBye(timer);
				return this;
			}
			
			
			
			// Nearest centroid model to predict labels
			NearestCentroid model = null;
			EntryPair<int[], double[]> label_dist;
			
			
			// Keep track of TSS (sum of barycentric distances)
			double maxCost = Double.NEGATIVE_INFINITY;
			tss = Double.POSITIVE_INFINITY;
			ArrayList<double[]> new_centroids;
			
			for(iter = 0; iter < maxIter; iter++) {
				
				// Get labels for nearest centroids
				try {
					model = new NearestCentroid(CentroidUtils.centroidsToMatrix(centroids, false), 
						VecUtils.arange(k), new NearestCentroidParameters()
							.setScale(false) // already scaled maybe
							.setSeed(getSeed())
							.setMetric(getSeparabilityMetric())
							.setVerbose(false)).fit();
				} catch(NaNException NaN) {
					/*
					 * If they metric used produces lots of infs or -infs, it 
					 * makes it hard if not impossible to effectively segment the
					 * input space. Thus, the centroid assignment portion below can
					 * yield a zero count (denominator) for one or more of the centroids
					 * which makes the entire row NaN. We should tell the user to
					 * try a different metric, if that's the case.
					 *
					error(new IllegalClusterStateException(dist_metric.getName()+" produced an entirely " +
					  "infinite distance matrix, making it difficult to segment the input space. Try a different " +
					  "metric."));
					 */
					this.k = 1;
					warn("(dis)similarity metric cannot partition space without propagating Infs. Returning one cluster");
					
					labelFromSingularK(X);
					fitSummary.add(new Object[]{ iter, converged, tss, tss, nan, nan, timer.wallTime() });
					sayBye(timer);
					return this;
				}
				
				label_dist = model.predict(X);
				
				// unpack the EntryPair
				labels = label_dist.getKey();
				
				// Start by computing TSS using barycentric dist
				double system_cost = 0.0;
				double[] centroid, new_centroid;
				new_centroids = new ArrayList<>(k);
				for(int i = 0; i < k; i++) {
					centroid = centroids.get(i);
					new_centroid = new double[n];
					
					// Compute the current cost for each cluster,
					// break if difference in TSS < tol. Otherwise
					// update the centroids to means of clusters.
					// We can compute what the new clusters will be
					// here, but don't assign yet
					int label, count = 0;
					double clust_cost = 0;
					for(int row = 0; row < m; row++) {
						label = labels[row];
						double diff;
						
						if(label == i) {
							for(int j = 0; j < n; j++) {
								new_centroid[j] += X[row][j];
								diff = X[row][j] - centroid[j];
								clust_cost += diff * diff;
							}
							
							// number in cluster
							count++;
						}
					}
					
					// Update the new centroid (currently a sum) to be a mean
					for(int j = 0; j < n; j++)
						new_centroid[j] /= (double)count;
					new_centroids.add(new_centroid);
					
					// Update system cost
					system_cost += clust_cost;
					
				} // end centroid re-assignment
				
				// Add current state to fitSummary
				fitSummary.add(new Object[]{ iter, converged, maxCost, tss, nan, nan, timer.wallTime() });
				
				
				// Assign new centroids
				centroids = new_centroids;
				double diff = tss - system_cost;	// results in Inf on first iteration
				tss = system_cost;
				
				// if diff is Inf, this is the first pass. Max is always first pass
				if(Double.isInfinite(diff))
					maxCost = tss; // should always stay the same..
				
				
				
				
				// Check for convergence
				if( FastMath.abs(diff) < tolerance ) {	// Inf always returns false in comparison
					// Did converge
					converged = true;
					iter++; // Going to break and miss this..
					break;
				}
				
			} // end iterations
			
			
			// reorder labels, then get wss and bss...
			reorderLabelsAndCentroids();
			this.wss = computeWSS(this.centroids, this.data.getDataRef(), this.labels);
			double wss_sum = VecUtils.sum(wss);
			this.bss = tss - wss_sum;
			

			// last one...
			fitSummary.add(new Object[]{ 
				iter, 
				converged, 
				maxCost, 
				tss, wss_sum, bss,
				timer.wallTime() });
			
			if(!converged)
				warn("algorithm did not converge");
				
			
			// wrap things up, create summary..
			sayBye(timer);
			
			
			return this;
		}
			
	}
	

	@Override
	public Algo getLoggerTag() {
		return com.clust4j.log.Log.Tag.Algo.KMEANS;
	}

	@Override
	protected Object[] getModelFitSummaryHeaders() {
		return new Object[]{
			"Iter. #","Converged","Max TSS","Min TSS","End WSS","End BSS","Wall"
		};
	}
}
