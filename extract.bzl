def sanitize(x):
  return x.strip() \
    .replace(':', '') \
    .replace(' ', '-') \
    .replace('.', '-') \
    .replace('_', '-') \
    .replace('/', '-') \
    .replace('--', '-') \
    .lower()

def extract(rule, path):
  name = sanitize(rule + '-' + path)

  native.genrule(
    name = name,
    out = path.split('/')[-1],
    cmd = 'cp $(location ' + rule + ')/' + path + ' $OUT',
  )

  return ':' + name
