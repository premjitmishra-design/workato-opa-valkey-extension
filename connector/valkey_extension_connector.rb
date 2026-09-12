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
    get("http://localhost/ext/#{connection['extension_name']}/health").
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
        get("http://localhost/ext/#{connection['extension_name']}/health").
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

        post("http://localhost/ext/#{connection['extension_name']}/get", key: input['key']).
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

        get("http://localhost/ext/#{connection['extension_name']}/keys", params).
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
    }
  }
}
