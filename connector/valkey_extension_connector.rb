{
  title: 'OPA Valkey extension',

  secure_tunnel: true,

  connection: {
    fields: [
      {
        name: 'extension_name',
        label: 'Extension name',
        hint: 'The key this jar is registered under, under `extensions:` in the Agent\'s ' \
          'conf/config.yml (see conf/config.yml in the opa-valkey-extension repo). Sets the ' \
          'path the Agent mounts it at: /ext/&lt;extension_name&gt;/...',
        default: 'valkey',
        optional: false
      }
    ],

    # ValkeyExtension has no auth of its own (unlike the reference SDK's SecurityExtension,
    # which checks a shared `secret`) - whatever the Agent/network already enforces in front
    # of /ext/* is the only access control here, so there's nothing for `authorization` to
    # apply to outgoing requests.
    authorization: {
      type: 'none'
    },

    # Required for secure_tunnel connectors: tells Workato to route every request through the
    # on-prem agent's tunnel instead of from Workato's own infrastructure. Without this header,
    # secure_tunnel: true alone does not enforce the on-prem routing.
    apply: lambda do
      headers('X-Workato-Connector': 'enforce')
    end

    # No base_uri: once secure_tunnel routes every request through the on-prem agent,
    # requests can address it directly with an absolute http://localhost/... URL (resolved
    # on the Agent's side), which is what `test` and every action below do.
  },

  test: lambda do |connection|
    get("http://localhost/ext/#{connection['extension_name']}/health").headers('X-Workato-Connector': 'enforce').
      after_error_response(/.*/) do |code, body, _header, message|
        error("Could not reach the ValkeyExtension OPA extension (#{code}): #{message} - #{body}")
      end
  end,

  actions: {
    health_check: {
      title: 'Check Valkey health',
      subtitle: 'Check connectivity to Valkey through the OPA extension',

      description: lambda do |_input, _picklist_label|
        "Check <span class='provider'>Valkey</span> health via the on-prem extension"
      end,

      input_fields: lambda do |_object_definitions|
        []
      end,

      execute: lambda do |connection, _input|
        get("http://localhost/ext/#{connection['extension_name']}/health").headers('X-Workato-Connector': 'enforce').
          after_error_response(/.*/) do |code, body, _header, message|
            error("Health check failed (#{code}): #{message} - #{body}")
          end
      end,

      output_fields: lambda do |_object_definitions|
        [
          { name: 'status', label: 'Status', hint: '"UP" or "DOWN"' },
          { name: 'pong', label: 'Pong response' },
          { name: 'error', label: 'Error message', hint: 'Present only when status is DOWN' }
        ]
      end,

      sample_output: lambda do |_connection, _input|
        { status: 'UP', pong: 'PONG' }
      end
    },

    set_key: {
      title: 'Set key',
      subtitle: 'Add or overwrite a Valkey cache entry, with an optional TTL',

      description: lambda do |input, _picklist_label|
        "Set <span class='provider'>#{input['key'] || 'key'}</span> in Valkey"
      end,

      input_fields: lambda do |_object_definitions|
        [
          {
            name: 'key',
            label: 'Key',
            hint: 'The Valkey key to create or overwrite, e.g. user:1001:name',
            optional: false
          },
          {
            name: 'value',
            label: 'Value',
            hint: 'The string value to store under this key.',
            optional: false
          },
          {
            name: 'ttl_seconds',
            label: 'TTL (seconds)',
            type: 'integer',
            hint: 'Optional expiry, in seconds. Leave blank for no expiry.',
            optional: true
          }
        ]
      end,

      execute: lambda do |connection, input|
        error('Provide a key to set') if input['key'].blank?
        error('Provide a value to set') if input['value'].blank?

        payload = { key: input['key'], value: input['value'] }
        payload[:ttlSeconds] = input['ttl_seconds'] if input['ttl_seconds'].present?

        post("http://localhost/ext/#{connection['extension_name']}/set", payload).headers('X-Workato-Connector': 'enforce').
          after_error_response(/.*/) do |code, body, _header, message|
            error(body.is_a?(Hash) && body['error'] ? body['error'] : "#{message}: #{body}")
          end
      end,

      output_fields: lambda do |_object_definitions|
        [
          { name: 'key', label: 'Key' },
          { name: 'set', label: 'Set', type: 'boolean' },
          { name: 'ttlSeconds', label: 'TTL (seconds)', type: 'integer', hint: 'Present only when a TTL was applied' }
        ]
      end,

      sample_output: lambda do |_connection, _input|
        { key: 'user:1001:name', set: true }
      end
    },

    set_keys: {
      title: 'Set keys (batch)',
      subtitle: 'Add or overwrite multiple Valkey cache entries in one call',

      description: lambda do |input, _picklist_label|
        count = input['entries'].is_a?(Array) ? input['entries'].size : 0
        "Set <span class='provider'>#{count} key(s)</span> in Valkey"
      end,

      input_fields: lambda do |_object_definitions|
        [
          {
            name: 'entries',
            label: 'Entries',
            type: 'array',
            of: 'object',
            optional: false,
            properties: [
              {
                name: 'key',
                label: 'Key',
                hint: 'The Valkey key to create or overwrite, e.g. user:1001:name',
                optional: false
              },
              {
                name: 'value',
                label: 'Value',
                hint: 'The string value to store under this key.',
                optional: false
              },
              {
                name: 'ttl_seconds',
                label: 'TTL (seconds)',
                type: 'integer',
                hint: 'Optional expiry, in seconds. Leave blank for no expiry.',
                optional: true
              }
            ]
          }
        ]
      end,

      execute: lambda do |connection, input|
        error('Provide at least one entry to set') if input['entries'].blank?

        entries = input['entries'].map do |entry|
          error('Each entry requires a key') if entry['key'].blank?
          error("Entry `#{entry['key']}` requires a value") if entry['value'].blank?

          payload = { key: entry['key'], value: entry['value'] }
          payload[:ttlSeconds] = entry['ttl_seconds'] if entry['ttl_seconds'].present?
          payload
        end

        post("http://localhost/ext/#{connection['extension_name']}/set-batch", entries: entries).headers('X-Workato-Connector': 'enforce').
          after_error_response(/.*/) do |code, body, _header, message|
            error(body.is_a?(Hash) && body['error'] ? body['error'] : "#{message}: #{body}")
          end
      end,

      output_fields: lambda do |_object_definitions|
        [
          { name: 'keys', label: 'Keys', type: 'array', of: 'string' },
          { name: 'count', label: 'Count', type: 'integer' },
          { name: 'set', label: 'Set', type: 'boolean' }
        ]
      end,

      summarize_output: ['keys'],

      sample_output: lambda do |_connection, _input|
        { keys: ['user:1001:name', 'user:1002:name'], count: 2, set: true }
      end
    },

    get_key: {
      title: 'Get key',
      subtitle: 'Retrieve a single Valkey key, auto-detecting its type',

      description: lambda do |input, _picklist_label|
        "Get <span class='provider'>#{input['key'] || 'key'}</span> from Valkey"
      end,

      input_fields: lambda do |_object_definitions|
        [
          {
            name: 'key',
            label: 'Key',
            hint: 'The Valkey key to retrieve, e.g. user:1001:name',
            optional: false
          }
        ]
      end,

      execute: lambda do |connection, input|
        error('Provide a key to retrieve') if input['key'].blank?

        post("http://localhost/ext/#{connection['extension_name']}/get", key: input['key']).headers('X-Workato-Connector': 'enforce').
          after_error_response(/.*/) do |code, body, _header, message|
            error(body.is_a?(Hash) && body['error'] ? body['error'] : "#{message}: #{body}")
          end
      end,

      output_fields: lambda do |_object_definitions|
        [
          { name: 'key', label: 'Key' },
          {
            name: 'type',
            label: 'Type',
            hint: 'string | hash | list | set | zset | none (key not found)'
          },
          { name: 'found', label: 'Found', type: 'boolean' },
          {
            name: 'value',
            label: 'Value',
            hint: 'Shape depends on `type`: a plain string for "string", an object for ' \
              '"hash", an array of strings for "list"/"set", or an array of ' \
              '{member, score} objects for "zset". Absent when `found` is false.'
          }
        ]
      end,

      sample_output: lambda do |_connection, _input|
        { key: 'user:1001:name', type: 'string', found: true, value: 'Ada Lovelace' }
      end
    },

    list_keys: {
      title: 'List keys',
      subtitle: 'List Valkey keys matching a glob pattern',

      description: lambda do |input, _picklist_label|
        "List keys matching <span class='provider'>#{input['pattern'] || '*'}</span> in Valkey"
      end,

      input_fields: lambda do |_object_definitions|
        [
          {
            name: 'pattern',
            label: 'Pattern',
            hint: 'Glob pattern, e.g. user:*. Defaults to * (every key) when left blank.',
            optional: true
          }
        ]
      end,

      execute: lambda do |connection, input|
        params = {}
        params['pattern'] = input['pattern'] if input['pattern'].present?

        get("http://localhost/ext/#{connection['extension_name']}/keys", params).headers('X-Workato-Connector': 'enforce').
          after_error_response(/.*/) do |code, body, _header, message|
            error("List keys failed (#{code}): #{message} - #{body}")
          end
      end,

      output_fields: lambda do |_object_definitions|
        [
          { name: 'pattern', label: 'Pattern' },
          { name: 'keys', label: 'Keys', type: 'array', of: 'string' },
          { name: 'count', label: 'Count', type: 'integer' }
        ]
      end,

      summarize_output: ['keys'],

      sample_output: lambda do |_connection, _input|
        { pattern: '*', keys: ['user:1001:name', 'user:1002'], count: 2 }
      end
    },

    search_keys: {
      title: 'Search keys',
      subtitle: 'Search Valkey keys matching a pattern and return each entry\'s value',

      description: lambda do |input, _picklist_label|
        "Search <span class='provider'>#{input['pattern'] || '*'}</span> in Valkey"
      end,

      input_fields: lambda do |_object_definitions|
        [
          {
            name: 'pattern',
            label: 'Pattern',
            hint: 'Glob pattern, e.g. user:*. Defaults to * (every key) when left blank.',
            optional: true
          }
        ]
      end,

      execute: lambda do |connection, input|
        params = {}
        params['pattern'] = input['pattern'] if input['pattern'].present?

        get("http://localhost/ext/#{connection['extension_name']}/search", params).headers('X-Workato-Connector': 'enforce').
          after_error_response(/.*/) do |code, body, _header, message|
            error("Search failed (#{code}): #{message} - #{body}")
          end
      end,

      output_fields: lambda do |_object_definitions|
        [
          { name: 'pattern', label: 'Pattern' },
          {
            name: 'entries',
            label: 'Entries',
            type: 'array',
            of: 'object',
            properties: [
              { name: 'key', label: 'Key' },
              {
                name: 'type',
                label: 'Type',
                hint: 'string | hash | list | set | zset | none (key not found)'
              },
              { name: 'found', label: 'Found', type: 'boolean' },
              {
                name: 'value',
                label: 'Value',
                hint: 'Shape depends on `type`, same as Get key\'s `value` output.'
              }
            ]
          },
          { name: 'count', label: 'Count', type: 'integer' }
        ]
      end,

      summarize_output: ['entries'],

      sample_output: lambda do |_connection, _input|
        {
          pattern: 'user:1001:*',
          entries: [
            { key: 'user:1001:name', type: 'string', found: true, value: 'Ada Lovelace' },
            { key: 'user:1001:email', type: 'string', found: true, value: 'ada@example.com' }
          ],
          count: 2
        }
      end
    },

    semantic_search: {
      title: 'Semantic search',
      subtitle: 'Vector similarity (KNN) search against a Valkey Search index',

      description: lambda do |input, _picklist_label|
        "Semantic search <span class='provider'>#{input['index'] || 'index'}</span> in Valkey"
      end,

      help: lambda do |_input, _picklist_label|
        'Runs a K-nearest-neighbors vector search over an index already created with the ' \
        '<a href="https://valkey.io/topics/search/" target="_blank">Valkey Search</a> module ' \
        '(FT.CREATE with a vector field). This action only queries an existing index - it does ' \
        'not create one, and the extension\'s Valkey server must have the valkey-search module ' \
        'loaded.'
      end,

      input_fields: lambda do |_object_definitions|
        [
          {
            name: 'index',
            label: 'Index name',
            hint: 'Name of the Valkey Search index to query, e.g. idx:docs',
            optional: false
          },
          {
            name: 'vector_field',
            label: 'Vector field',
            hint: 'Name of the indexed vector field to search against, e.g. embedding',
            optional: false
          },
          {
            name: 'vector',
            label: 'Query vector',
            type: 'array',
            of: 'number',
            hint: 'The query embedding, e.g. from an embeddings model, as an array of numbers.',
            optional: false
          },
          {
            name: 'top_k',
            label: 'Top K',
            type: 'integer',
            hint: 'Number of nearest neighbors to return. Defaults to 10.',
            optional: true
          },
          {
            name: 'return_fields',
            label: 'Return fields',
            type: 'array',
            of: 'string',
            hint: 'Optional list of stored fields to return per match. Leave blank to return every stored field.',
            optional: true
          }
        ]
      end,

      execute: lambda do |connection, input|
        error('Provide an index to search') if input['index'].blank?
        error('Provide a vector field to search') if input['vector_field'].blank?
        error('Provide a query vector') if input['vector'].blank?

        payload = {
          index: input['index'],
          vectorField: input['vector_field'],
          vector: input['vector']
        }
        payload[:topK] = input['top_k'] if input['top_k'].present?
        payload[:returnFields] = input['return_fields'] if input['return_fields'].present?

        post("http://localhost/ext/#{connection['extension_name']}/semantic-search", payload).headers('X-Workato-Connector': 'enforce').
          after_error_response(/.*/) do |code, body, _header, message|
            error(body.is_a?(Hash) && body['error'] ? body['error'] : "#{message}: #{body}")
          end
      end,

      output_fields: lambda do |_object_definitions|
        [
          { name: 'index', label: 'Index name' },
          {
            name: 'matches',
            label: 'Matches',
            type: 'array',
            of: 'object',
            properties: [
              { name: 'key', label: 'Key', hint: 'The matched document/hash key' },
              { name: 'score', label: 'Score', type: 'number', hint: 'Vector distance - lower means more similar' },
              { name: 'fields', label: 'Fields', type: 'object', hint: 'The match\'s other stored fields' }
            ]
          },
          { name: 'count', label: 'Count', type: 'integer' }
        ]
      end,

      summarize_output: ['matches'],

      sample_output: lambda do |_connection, _input|
        {
          index: 'idx:docs',
          matches: [
            { key: 'doc:1', score: 0.08, fields: { title: 'Ada Lovelace', text: 'first computer programmer' } },
            { key: 'doc:2', score: 0.21, fields: { title: 'Alan Turing', text: 'computability theory' } }
          ],
          count: 2
        }
      end
    }
  }
}
