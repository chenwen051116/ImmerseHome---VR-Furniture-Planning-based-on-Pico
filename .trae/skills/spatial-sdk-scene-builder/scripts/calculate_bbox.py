#!/usr/bin/env python3

import sys
import os
import argparse
import json
import trimesh
from pxr import Usd, UsdGeom

def calculate_bbox_trimesh(file_path):
    try:
        scene = trimesh.load(file_path, force='scene')
        bounds = scene.bounds
        
        if bounds is None:
            raise ValueError("Could not compute bounding box. Model might be empty.")
            
        min_bounds = bounds[0]
        max_bounds = bounds[1]
        
        width = max_bounds[0] - min_bounds[0]
        height = max_bounds[1] - min_bounds[1]
        depth = max_bounds[2] - min_bounds[2]
        
        return {
            "min": [float(min_bounds[0]), float(min_bounds[1]), float(min_bounds[2])],
            "max": [float(max_bounds[0]), float(max_bounds[1]), float(max_bounds[2])],
            "dimensions": {
                "width": float(width),
                "height": float(height),
                "depth": float(depth)
            }
        }
    except Exception as e:
        raise RuntimeError(f"Failed to extract bounding box: {str(e)}")

def calculate_bbox_usd(file_path):
    try:
        stage = Usd.Stage.Open(file_path)
        if not stage:
            raise ValueError(f"Failed to open USD file: {file_path}")
            
        bbox_cache = UsdGeom.BBoxCache(Usd.TimeCode.Default(), ['default', 'proxy', 'render'])
        root_prim = stage.GetPseudoRoot()
        bound = bbox_cache.ComputeWorldBound(root_prim)
        
        # bound is a Gf.BBox3d, bound.ComputeAlignedRange() returns a Gf.Range3d
        aligned_range = bound.ComputeAlignedRange()
        
        if aligned_range.IsEmpty():
            raise ValueError("Could not compute bounding box. Model might be empty.")
            
        min_bounds = aligned_range.GetMin()
        max_bounds = aligned_range.GetMax()
        
        width = max_bounds[0] - min_bounds[0]
        height = max_bounds[1] - min_bounds[1]
        depth = max_bounds[2] - min_bounds[2]
        
        return {
            "min": [float(min_bounds[0]), float(min_bounds[1]), float(min_bounds[2])],
            "max": [float(max_bounds[0]), float(max_bounds[1]), float(max_bounds[2])],
            "dimensions": {
                "width": float(width),
                "height": float(height),
                "depth": float(depth)
            }
        }
    except Exception as e:
        raise RuntimeError(f"Failed to extract bounding box: {str(e)}")

def main():
    parser = argparse.ArgumentParser(description='Extract the bounding box dimensions from a 3D model file')
    parser.add_argument('file_path', help='Absolute or relative path to the 3D model file')
    
    args = parser.parse_args()
    file_path = args.file_path
    
    if not os.path.exists(file_path):
        print(f"Error: File not found: {file_path}")
        sys.exit(1)
        
    ext = os.path.splitext(file_path)[1].lower()
    
    print(f"Parsing {ext} file: {file_path}")
    
    try:
        if ext in ['.glb', '.gltf', '.obj', '.stl']:
            bbox = calculate_bbox_trimesh(file_path)
        elif ext == '.usdz':
            bbox = calculate_bbox_usd(file_path)
        else:
            print(f"Error: Unsupported file format: {ext}. Only .glb, .gltf, and .usdz are supported.")
            sys.exit(1)
            
        print("Bounding Box Data:")
        
        min_vals = bbox['min']
        max_vals = bbox['max']
        
        print(f"Min: [{', '.join(f'{x:.4f}' for x in min_vals)}]")
        print(f"Max: [{', '.join(f'{x:.4f}' for x in max_vals)}]")
            
        dims = bbox['dimensions']
        print(f"Dimensions: Width={dims['width']:.4f}, Height={dims['height']:.4f}, Depth={dims['depth']:.4f}")
        
    except Exception as e:
        print(f"Error: {str(e)}")
        sys.exit(1)

if __name__ == '__main__':
    main()
