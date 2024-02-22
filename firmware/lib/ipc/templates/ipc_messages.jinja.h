#pragma once

// Autogenerated - do not modify

{% for hdr in proto_headers %}
#include "{{hdr}}"
{% endfor %}
#include <stdint.h>

typedef uint32_t ipc_port_t;

{% for struct in messages.structs %}
typedef struct {
{% for field in struct.fields %}
  {{field}};
{% endfor %}
} {{struct.name}};

{% endfor %}
typedef enum {
{% for struct in messages.structs %}
  IPC_{{struct.name.upper()[:-2]}},
{% endfor %}
{% for proto in messages.protos %}
  IPC_PROTO_{{'_'.join(proto.name.upper().split('_')[1:])}},
{% endfor %}
  IPC_{{port_name.upper()}}_END,
} ipc_{{port_name}}_msg_t;
