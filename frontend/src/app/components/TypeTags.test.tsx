import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { TypeTags } from './TypeTags'

describe('TypeTags', () => {
  it('renders every Pokemon type as a tag', () => {
    render(<TypeTags types={['grass', 'poison']} />)

    expect(screen.getByText('grass')).toBeInTheDocument()
    expect(screen.getByText('poison')).toBeInTheDocument()
  })

  it('renders no tags when the Pokemon has no types', () => {
    const { container } = render(<TypeTags types={[]} />)

    expect(container.querySelectorAll('span')).toHaveLength(0)
  })
})
