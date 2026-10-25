'use strict'

// Architecture boundary: audit owns its API and never imports admin's compatibility facade.
function isForbiddenFeatureDependency(source, target) {
    return source.name === 'features'
        && source.feature === 'audit'
        && target.name === 'features'
        && target.feature === 'admin'
}

module.exports = { isForbiddenFeatureDependency }
