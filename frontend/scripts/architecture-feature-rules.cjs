'use strict'

// Architecture boundary: audit owns its API and never imports admin's compatibility facade.
function isForbiddenFeatureDependency(source, target) {
    if (source.name !== 'features' || target.name !== 'features') return false
    return (source.feature === 'audit' && target.feature === 'admin')
        || (source.feature === 'chat' && target.feature === 'knowledge')
        || (source.feature === 'knowledge' && target.feature === 'chat')
}

module.exports = { isForbiddenFeatureDependency }
