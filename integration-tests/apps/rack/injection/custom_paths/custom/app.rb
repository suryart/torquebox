class App

  def call(env)
    queue = TorqueBox.fetch('/queues/inject-custom')

    [200, {'Content-Type' => 'text/html'}, %Q{it worked
<div id='queue-injected'>#{queue.nil? ? 'no' : 'yes'}</div>
}]
  end
end
