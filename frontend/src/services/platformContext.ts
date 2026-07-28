/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
import modelConfigService from '@/services/modelConfig';
import { semEvoSQLService, type OperatorView } from '@/services/semevosql';

export interface PlatformReadiness {
  chatModelReady: boolean;
  embeddingModelReady: boolean;
  rerankModelReady: boolean;
  ready: boolean;
}

let operatorCache: OperatorView | undefined;
let operatorPromise: Promise<OperatorView> | undefined;
let readinessCache: PlatformReadiness | undefined;
let readinessPromise: Promise<PlatformReadiness> | undefined;

export const platformContext = {
  async operator(force = false): Promise<OperatorView> {
    if (force) {
      operatorCache = undefined;
      operatorPromise = undefined;
    }
    if (operatorCache) return operatorCache;
    if (!operatorPromise) {
      operatorPromise = semEvoSQLService
        .currentOperator()
        .then(value => {
          operatorCache = value;
          return value;
        })
        .finally(() => {
          operatorPromise = undefined;
        });
    }
    return operatorPromise;
  },

  async readiness(force = false): Promise<PlatformReadiness> {
    if (force) {
      readinessCache = undefined;
      readinessPromise = undefined;
    }
    if (readinessCache) return readinessCache;
    if (!readinessPromise) {
      readinessPromise = modelConfigService
        .checkReady()
        .then(value => {
          readinessCache = value;
          return value;
        })
        .finally(() => {
          readinessPromise = undefined;
        });
    }
    return readinessPromise;
  },

  invalidateOperator() {
    operatorCache = undefined;
    operatorPromise = undefined;
  },

  invalidateReadiness() {
    readinessCache = undefined;
    readinessPromise = undefined;
  },
};
